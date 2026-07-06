package com.schale.queue.api.queue;

import com.schale.queue.api.queue.SubscriptionRegistry.Subscription;
import com.schale.queue.api.queue.dto.AdmissionNotice;
import com.schale.queue.api.queue.dto.QueuePositionResponse;
import com.schale.queue.core.domain.queue.AdmissionTokenService;
import com.schale.queue.core.domain.queue.QueueService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 대기열 실시간 스트림 서비스(Phase 3 ⑤). 구독자에게 순번 갱신과 입장 알림을 SSE 로 밀어준다.
 *
 * <p><b>폴링 모델.</b> 대기 순번은 "내 앞사람이 빠질 때마다" 바뀌므로 본질적으로 주기적
 * {@code ZRANK} 조회가 필요하다. 입장 감지를 같은 폴링 tick 에 얹어, 추가 인프라(Pub/Sub) 없이
 * 순번·입장을 함께 처리한다. 이벤트 기반 전환은 Phase 4(Kafka/EDA)의 몫이다.
 *
 * <p><b>벌크 브로드캐스트(리뷰 M8, Phase 5 부하 실측).</b> 폴러는 단일 스케줄러지만, 구독자별
 * Redis 개별 왕복(ZRANK+EXISTS ×N 직렬)은 구독 N 에 비례해 tick 을 폴링 주기 밖으로 밀어냈다
 * (실측 2,000 구독 = tick ~1.5s). tick 마다 <b>상품별로 토큰 MGET 1회 + 순번 파이프라인 1회 +
 * ZCARD 1회</b>만 왕복하고, emitter 순회는 인메모리 send 만 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueStreamService {

    private static final String EVENT_POSITION = "position";
    private static final String EVENT_ADMITTED = "admitted";

    private final QueueService queueService;
    private final AdmissionTokenService admissionTokenService;
    private final SubscriptionRegistry registry;
    private final QueueSseProperties properties;

    /**
     * 구독을 시작한다. emitter 를 만들어 레지스트리에 등록하고, 생명주기 콜백으로 정리를 건다.
     * 첫 순번/입장 상태를 즉시 1회 밀어 초기 화면을 채운다(이후는 폴러가 갱신).
     */
    public SseEmitter subscribe(Long goodsId, Long memberId) {
        Subscription key = new Subscription(goodsId, memberId);
        SseEmitter emitter = new SseEmitter(properties.getEmitterTimeout().toMillis());

        SseEmitter previous = registry.add(key, emitter);
        if (previous != null) {
            previous.complete();    // 재연결: 같은 회원의 이전 연결을 닫는다.
        }
        emitter.onCompletion(() -> registry.remove(key, emitter));
        emitter.onTimeout(() -> {
            registry.remove(key, emitter);
            emitter.complete();
        });
        emitter.onError(e -> registry.remove(key, emitter));

        push(key, emitter);     // 초기 1회 전송(best-effort).
        return emitter;
    }

    /**
     * 폴링 tick. 등록된 모든 구독에 대해 순번을 갱신하거나 입장을 알린다.
     * 불변 스냅샷을 돌므로, 순회 중 입장 처리로 레지스트리가 줄어도 안전하다.
     *
     * <p>상품별로 묶어 Redis 왕복을 상수 회수(토큰 MGET + 순번 파이프라인 + ZCARD)로 만든다.
     * 벌크 조회가 실패하면 해당 상품 그룹만 이번 tick 을 건너뛴다(다음 tick 재시도).
     */
    @Scheduled(fixedDelayString = "${schale.queue.sse.poll-interval:1s}")
    public void broadcast() {
        Map<Long, List<Map.Entry<Subscription, SseEmitter>>> byGoods = registry.snapshot().entrySet().stream()
            .collect(Collectors.groupingBy(entry -> entry.getKey().goodsId()));

        byGoods.forEach((goodsId, subscriptions) -> {
            List<Long> memberIds = subscriptions.stream().map(entry -> entry.getKey().memberId()).toList();
            Set<Long> admitted;
            Map<Long, Long> positions;
            long waiting;
            try {
                admitted = admissionTokenService.membersWithValidToken(goodsId, memberIds);
                positions = queueService.getPositions(goodsId, memberIds);
                waiting = queueService.size(goodsId);
            } catch (RuntimeException e) {
                log.warn("SSE 벌크 조회 실패 — goodsId={} 구독 {}건 이번 tick 건너뜀", goodsId, subscriptions.size(), e);
                return;
            }

            for (Map.Entry<Subscription, SseEmitter> entry : subscriptions) {
                Subscription key = entry.getKey();
                if (admitted.contains(key.memberId())) {
                    sendAdmitted(key, entry.getValue());
                } else if (positions.containsKey(key.memberId())) {
                    sendPosition(key, entry.getValue(), positions.get(key.memberId()), waiting);
                }
                // 둘 다 아니면(dequeue~발급 전이 중이거나 이탈) 이번 tick 은 건너뛴다(타임아웃이 정리).
            }
        });
    }

    /**
     * 한 구독에 현재 상태를 전송한다(구독 직후의 초기 1회 경로 — tick 은 {@link #broadcast} 벌크 경로).
     * <ol>
     *   <li>입장 토큰이 있으면 {@code admitted} 를 보내고 스트림을 종료한다(우선 검사).</li>
     *   <li>아직 대기 중이면 {@code position} 을 보낸다.</li>
     *   <li>둘 다 아니면(dequeue~issue 전이 중이거나 이탈) 건너뛴다(타임아웃이 정리).</li>
     * </ol>
     */
    private void push(Subscription key, SseEmitter emitter) {
        try {
            if (admissionTokenService.hasValidToken(key.goodsId(), key.memberId())) {
                sendAdmitted(key, emitter);
                return;
            }
            OptionalLong position = queueService.getPosition(key.goodsId(), key.memberId());
            if (position.isPresent()) {
                sendPosition(key, emitter, position.getAsLong(), queueService.size(key.goodsId()));
            }
        } catch (RuntimeException e) {
            // 초기 1회 전송은 best-effort — 조회 실패가 구독 수립(HTTP 응답)을 깨면 안 된다.
            log.warn("SSE 초기 전송 조회 실패 goodsId={} memberId={} (폴러가 재시도)", key.goodsId(), key.memberId(), e);
        }
    }

    /** {@code admitted} 전송 후 스트림을 종료한다. 전송 실패 시 구독을 정리한다. */
    private void sendAdmitted(Subscription key, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                .name(EVENT_ADMITTED)
                .data(new AdmissionNotice(key.goodsId())));
            emitter.complete();
            registry.remove(key, emitter);
        } catch (IOException | RuntimeException e) {
            cleanup(key, emitter, e);
        }
    }

    /** {@code position} 전송. 전송 실패 시 구독을 정리한다. */
    private void sendPosition(Subscription key, SseEmitter emitter, long position, long waiting) {
        try {
            emitter.send(SseEmitter.event()
                .name(EVENT_POSITION)
                .data(new QueuePositionResponse(position, waiting)));
        } catch (IOException | RuntimeException e) {
            cleanup(key, emitter, e);
        }
    }

    private void cleanup(Subscription key, SseEmitter emitter, Exception e) {
        if (e instanceof IOException) {
            // 클라이언트가 연결을 끊은 정상 종료 경로 — 조용히 정리한다.
            registry.remove(key, emitter);
        } else {
            log.warn("SSE 전송 실패로 구독 정리 goodsId={} memberId={}", key.goodsId(), key.memberId(), e);
            registry.remove(key, emitter);
        }
    }
}
