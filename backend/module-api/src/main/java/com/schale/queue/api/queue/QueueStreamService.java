package com.schale.queue.api.queue;

import com.schale.queue.api.queue.SubscriptionRegistry.Subscription;
import com.schale.queue.api.queue.dto.AdmissionNotice;
import com.schale.queue.api.queue.dto.QueuePositionResponse;
import com.schale.queue.core.domain.queue.AdmissionTokenService;
import com.schale.queue.core.domain.queue.QueueService;
import java.io.IOException;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 대기열 실시간 스트림 서비스(Phase 3 ⑤). 구독자에게 순번 갱신과 입장 알림을 SSE 로 밀어준다.
 *
 * <p><b>폴링 모델.</b> 대기 순번은 "내 앞사람이 빠질 때마다" 바뀌므로 본질적으로 주기적
 * {@code ZRANK} 조회가 필요하다. 입장 감지({@link AdmissionTokenService#hasValidToken})를
 * 같은 폴링 tick 에 얹어, 추가 인프라(Pub/Sub) 없이 순번·입장을 함께 처리한다. 이벤트 기반
 * 전환은 Phase 4(Kafka/EDA)의 몫이다.
 *
 * <p>폴러는 단일 스케줄러로 인스턴스 로컬 {@link SubscriptionRegistry} 의 emitter 만 순회한다.
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
     */
    @Scheduled(fixedDelayString = "${schale.queue.sse.poll-interval:1s}")
    public void broadcast() {
        registry.snapshot().forEach(this::push);
    }

    /**
     * 한 구독에 현재 상태를 전송한다.
     * <ol>
     *   <li>입장 토큰이 있으면 {@code admitted} 를 보내고 스트림을 종료한다(우선 검사).</li>
     *   <li>아직 대기 중이면 {@code position} 을 보낸다.</li>
     *   <li>둘 다 아니면(dequeue~issue 전이 중이거나 이탈) 이번 tick 은 건너뛴다(타임아웃이 정리).</li>
     * </ol>
     */
    private void push(Subscription key, SseEmitter emitter) {
        try {
            if (admissionTokenService.hasValidToken(key.goodsId(), key.memberId())) {
                emitter.send(SseEmitter.event()
                    .name(EVENT_ADMITTED)
                    .data(new AdmissionNotice(key.goodsId())));
                emitter.complete();
                registry.remove(key, emitter);
                return;
            }

            OptionalLong position = queueService.getPosition(key.goodsId(), key.memberId());
            if (position.isPresent()) {
                long waiting = queueService.size(key.goodsId());
                emitter.send(SseEmitter.event()
                    .name(EVENT_POSITION)
                    .data(new QueuePositionResponse(position.getAsLong(), waiting)));
            }
        } catch (IOException e) {
            // 클라이언트가 연결을 끊은 정상 종료 경로 — 조용히 정리한다.
            registry.remove(key, emitter);
        } catch (RuntimeException e) {
            log.warn("SSE 전송 실패로 구독 정리 goodsId={} memberId={}", key.goodsId(), key.memberId(), e);
            registry.remove(key, emitter);
        }
    }
}
