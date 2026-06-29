package com.schale.queue.worker.outbox;

import com.schale.queue.core.domain.outbox.EventOutbox;
import com.schale.queue.core.domain.outbox.OutboxStatus;
import com.schale.queue.core.domain.outbox.repository.EventOutboxRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아웃박스 릴레이(ADR-007, 폴링 퍼블리셔). 무유실 발행의 후반부를 담당한다.
 *
 * <p>{@code event_outbox} 의 {@code PENDING} 행을 주기적으로 폴링해 Kafka 로 발행하고, broker ack 를
 * 받은 행만 {@code SENT} 로 표시한다. 전송이 실패한 행은 {@code PENDING} 으로 남아 다음 틱에 재시도되므로
 * <b>at-least-once</b> 가 보장된다. (이미 워커에 있는 {@code PaymentExpiryWorker} 와 동일한 @Scheduled 패턴.)
 *
 * <p><b>발행 후 마킹 순서</b>: {@code send().get()} 로 ack 를 동기 확인한 뒤에만 {@link EventOutbox#markSent}
 * 한다. 발행은 성공했으나 markSent 커밋이 실패하는 드문 경우엔 그 행이 다음 틱에 재발행되어 Kafka 에 중복이
 * 생길 수 있으나, 컨슈머가 {@code processed_event} 로 멱등 처리하므로 안전하다(중복 흡수).
 *
 * <p>발행은 직렬화된 JSON 문자열({@code payload})을 그대로 보낸다(StringSerializer). 컨슈머는 JsonDeserializer
 * 로 역직렬화하므로, 릴레이는 이벤트 타입을 몰라도 되는 범용 구조다(새 이벤트 추가 시 릴레이 무변경).
 */
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final EventOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${schale.outbox.relay-interval:500ms}")
    @Transactional
    public void relay() {
        List<EventOutbox> pending = outboxRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING);
        if (pending.isEmpty()) {
            return;
        }
        int sent = 0;
        for (EventOutbox row : pending) {
            try {
                // ack 를 동기로 기다려, 발행이 확정된 행만 SENT 로 바꾼다(실패 시 PENDING 유지 → 다음 틱 재시도).
                kafkaTemplate.send(row.getTopic(), row.getMsgKey(), row.getPayload()).get();
                row.markSent(LocalDateTime.now(clock));
                sent++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("아웃박스 발행 중단 eventId={} → 다음 틱 재시도", row.getEventId(), e);
                break;   // 인터럽트 시 남은 행은 다음 틱으로 미룬다
            } catch (Exception e) {
                log.error("아웃박스 발행 실패 eventId={} topic={} → 다음 틱 재시도",
                    row.getEventId(), row.getTopic(), e);
                // markSent 생략 → PENDING 유지
            }
        }
        log.info("아웃박스 발행 {}건 (대상 {}건)", sent, pending.size());
    }
}
