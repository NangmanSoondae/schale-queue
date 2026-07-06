package com.schale.queue.api.queue;

import com.schale.queue.api.queue.SubscriptionRegistry.Subscription;
import com.schale.queue.api.queue.dto.AdmissionNotice;
import com.schale.queue.api.queue.dto.QueuePositionResponse;
import com.schale.queue.core.domain.queue.AdmissionTokenService;
import com.schale.queue.core.domain.queue.QueueService;
import com.schale.queue.core.domain.queue.QueueService.QueueSnapshot;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
     * <p><b>상품군 병렬 처리(리뷰2 M-5)</b>: 상품별 왕복을 순차로 돌면 tick 이 활성 상품 수 G 에
     * 선형(직렬 3G 왕복)이라, 이벤트 다수 동시 오픈 시 M8 과 같은 포화가 축만 바뀌어 재발한다.
     * 상품군마다 가상 스레드로 팬아웃하고, try-with-resources 의 close() 가 전 작업 완료를
     * 기다리므로 fixedDelay 특성상 tick 중첩은 없다(한 구독은 정확히 한 상품군에만 속해
     * emitter 가 작업 간에 공유되지 않는다).
     */
    @Scheduled(fixedDelayString = "${schale.queue.sse.poll-interval:1s}")
    public void broadcast() {
        Map<Long, List<Map.Entry<Subscription, SseEmitter>>> byGoods = registry.snapshot().entrySet().stream()
            .collect(Collectors.groupingBy(entry -> entry.getKey().goodsId()));
        if (byGoods.isEmpty()) {
            return;
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            byGoods.forEach((goodsId, subscriptions) -> executor.submit(() -> broadcastGoods(goodsId, subscriptions)));
        }
    }

    /**
     * 한 상품군의 구독들에 현재 상태를 전송한다. 왕복은 순번 스냅샷(ZCARD+ZRANK 단일 파이프라인,
     * 리뷰2 M-6) 1회 + 토큰 MGET 1회.
     *
     * <p><b>조회 순서 = 순번 먼저, 토큰 나중(리뷰2 L-1)</b>: 두 조회 사이에 입장(pop+발급 원자)이
     * 끼어든 회원은 "순번에 없음 + 토큰 있음"으로 잡혀 같은 tick 에 admitted 를 받는다(반대 순서면
     * 양쪽 다 없어 한 tick 밀렸다). 순번 스냅샷 시점에 아직 대기 중이던 회원이 조회 직후 입장해도,
     * admitted 우선 판정이라 position 이 아닌 admitted 가 나간다.
     *
     * <p><b>부분 실패 격리(리뷰2 L-4)</b>: 두 조회를 독립 try 로 감싼다 — 순번 조회가 실패해도
     * 시간 민감한 입장 통지는 진행하고, 토큰 조회가 실패해도 순번 갱신은 진행한다(ZSET 에 있는
     * 회원은 정의상 미입장이라 admitted 판정 없이 position 을 보내도 안전하다).
     */
    private void broadcastGoods(Long goodsId, List<Map.Entry<Subscription, SseEmitter>> subscriptions) {
        List<Long> memberIds = subscriptions.stream().map(entry -> entry.getKey().memberId()).toList();

        QueueSnapshot snapshot = null;
        try {
            snapshot = queueService.getPositionsSnapshot(goodsId, memberIds);
        } catch (RuntimeException e) {
            log.warn("SSE 순번 벌크 조회 실패 — goodsId={} 이번 tick 순번 갱신 생략", goodsId, e);
        }
        Set<Long> admitted = null;
        try {
            admitted = admissionTokenService.membersWithValidToken(goodsId, memberIds);
        } catch (RuntimeException e) {
            log.warn("SSE 토큰 벌크 조회 실패 — goodsId={} 이번 tick 입장 통지 생략", goodsId, e);
        }
        if (snapshot == null && admitted == null) {
            return;   // 양쪽 다 실패 — 다음 tick 재시도
        }

        for (Map.Entry<Subscription, SseEmitter> entry : subscriptions) {
            Subscription key = entry.getKey();
            if (admitted != null && admitted.contains(key.memberId())) {
                sendAdmitted(key, entry.getValue());
            } else if (snapshot != null && snapshot.positions().containsKey(key.memberId())) {
                sendPosition(key, entry.getValue(), snapshot.positions().get(key.memberId()), snapshot.waiting());
            }
            // 둘 다 아니면(dequeue~발급 전이 중이거나 이탈) 이번 tick 은 건너뛴다(타임아웃이 정리).
        }
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

    /**
     * {@code admitted} 전송 후 스트림을 종료한다.
     *
     * <p><b>소유권 선점(리뷰2 M-4)</b>: 초기 push(요청 스레드)와 폴링 tick 이 같은 구독을 병행
     * 처리할 수 있다 — 레지스트리 제거(원자적 2-인자 remove)에 성공한 쪽만 전송해, 클라이언트가
     * {@code admitted} 를 두 번 받는 창을 없앤다. 선점 후 전송이 실패하면 completeWithError 로
     * 스트림을 닫아 클라이언트 재연결을 유도한다(재구독의 초기 push 가 다시 admitted 를 보낸다).
     */
    void sendAdmitted(Subscription key, SseEmitter emitter) {   // 패키지 전용 — 소유권 계약을 테스트가 직접 검증
        if (!registry.remove(key, emitter)) {
            return;   // 다른 경로가 이미 소유권을 가져감 — 이중 전송 방지
        }
        try {
            emitter.send(SseEmitter.event()
                .name(EVENT_ADMITTED)
                .data(new AdmissionNotice(key.goodsId())));
            emitter.complete();
        } catch (IOException e) {
            // 클라이언트가 이미 끊김 — 조용히 종료(재구독 시 초기 push 가 admitted 재전송).
        } catch (RuntimeException e) {
            log.warn("admitted 전송 실패 goodsId={} memberId={}", key.goodsId(), key.memberId(), e);
            completeWithErrorQuietly(emitter, e);
        }
    }

    /** {@code position} 전송. 전송 실패 시 구독을 정리하고 스트림을 명시적으로 닫는다. */
    private void sendPosition(Subscription key, SseEmitter emitter, long position, long waiting) {
        try {
            emitter.send(SseEmitter.event()
                .name(EVENT_POSITION)
                .data(new QueuePositionResponse(position, waiting)));
        } catch (IOException e) {
            // 클라이언트가 연결을 끊은 정상 종료 경로 — 조용히 정리한다(컨테이너가 요청을 정리).
            registry.remove(key, emitter);
        } catch (RuntimeException e) {
            // 좀비 연결 방지(리뷰2 M-3): 레지스트리에서만 지우고 끝내면 HTTP 연결이 emitter 타임아웃
            // (기본 30분)까지 이벤트 0건인 채 열려 있고, 클라이언트는 오류 콜백도 못 받아 침묵 대기한다.
            // completeWithError 로 비동기 요청을 종료시켜 클라이언트 재연결을 트리거한다.
            log.warn("SSE 전송 실패로 구독 정리 goodsId={} memberId={}", key.goodsId(), key.memberId(), e);
            registry.remove(key, emitter);
            completeWithErrorQuietly(emitter, e);
        }
    }

    /** completeWithError 는 상태에 따라 IllegalStateException 을 던질 수 있어 감싼다(이미 완료된 emitter 등). */
    private void completeWithErrorQuietly(SseEmitter emitter, Exception cause) {
        try {
            emitter.completeWithError(cause);
        } catch (RuntimeException ignore) {
            // 이미 완료/타임아웃된 emitter — 추가 조치 불요.
        }
    }
}
