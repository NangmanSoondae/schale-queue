package com.schale.queue.worker.outbox;

import com.schale.queue.core.domain.outbox.EventOutbox;
import com.schale.queue.core.domain.outbox.OutboxStatus;
import com.schale.queue.core.domain.outbox.repository.EventOutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 아웃박스 릴레이(ADR-007, 폴링 퍼블리셔). 무유실 발행의 후반부를 담당한다.
 *
 * <p>{@code event_outbox} 의 {@code PENDING} 행을 주기적으로 폴링해 Kafka 로 발행하고, broker ack 를
 * 받은 행만 {@code SENT} 로 표시한다. 전송이 실패한 행은 {@code PENDING} 으로 남아 다음 틱에 재시도되므로
 * <b>at-least-once</b> 가 보장된다.
 *
 * <p><b>트랜잭션 범위(리뷰 M2)</b>: 과거엔 relay 전체가 {@code @Transactional} 이라 브로커 블로킹 send
 * 최대 100건이 <b>DB 트랜잭션(커넥션) 안에서</b> 수행됐고, {@code get()} 도 무기한이라 브로커 다운 시
 * 커넥션 풀이 잠식됐다. 지금은 ① 조회(단건 읽기) → ② 발행(트랜잭션 밖, {@code get(timeout)}) →
 * ③ 성공분만 짧은 벌크 UPDATE 로 SENT 마킹 — DB 커넥션 점유가 ③의 순간으로 줄어든다.
 *
 * <p><b>중복 발행 창</b>: {@code get(timeout)} 이 초과됐지만 브로커엔 나중에 도달한 경우, 또는 ③ 마킹
 * 실패 시 해당 행이 재발행될 수 있다 — 컨슈머의 {@code processed_event} 멱등 가드가 흡수한다(기존 시맨틱
 * 유지). 멀티 인스턴스 동시 폴링의 중복도 같은 이유로 안전하지만, 현 배포는 워커 단일 컨테이너라 경합이
 * 없어 SELECT ... SKIP LOCKED(클레임 상태 머신)는 도입하지 않는다 — 스케일아웃 시 재평가.
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
    private final com.schale.queue.worker.health.WorkerLiveness liveness;

    /** broker ack 대기 상한. 무기한 대기(리뷰 M2)가 릴레이 스레드를 브로커 다운 내내 묶는 것을 막는다. */
    @Value("${schale.outbox.send-timeout:10s}")
    private Duration sendTimeout;

    @Scheduled(fixedDelayString = "${schale.outbox.relay-interval:500ms}")
    public void relay() {
        // 생존 신고(리뷰2 H-2): 유일한 발행자라 wedge 감지가 가장 중요하다.
        // 기한 = 주기(500ms) + 최악 실행(타임아웃 걸린 send ~10s + 마킹) 여유 → 60s.
        liveness.beat("outbox-relay", Duration.ofSeconds(60));
        List<EventOutbox> pending = outboxRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING);
        if (pending.isEmpty()) {
            return;
        }
        List<Long> sentIds = new ArrayList<>(pending.size());
        // 인터럽트 플래그는 markSent 완료 후에 복원한다(리뷰2 M-11): 복원된 상태로 JDBC 커넥션을
        // 획득하면 실패해 '이미 ack 받은 행'의 마킹이 유실되고, graceful shutdown(스케줄러가 실행 중
        // 작업을 인터럽트) 때마다 그 행들이 재발행됐다(멱등이 흡수하지만 의도 무산 + 오도 로그).
        boolean interrupted = false;
        for (EventOutbox row : pending) {
            try {
                // ack 를 동기로 기다려, 발행이 확정된 행만 SENT 후보에 올린다(실패 시 PENDING 유지 → 다음 틱).
                kafkaTemplate.send(row.getTopic(), row.getMsgKey(), row.getPayload())
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                sentIds.add(row.getId());
            } catch (InterruptedException e) {
                interrupted = true;
                log.warn("아웃박스 발행 중단(인터럽트) eventId={} → 기발행분 마킹 후 종료", row.getEventId());
                break;   // 남은 행은 다음 틱(또는 재기동 후)으로 미룬다
            } catch (TimeoutException e) {
                // 브로커 응답 지연 — 이 틱은 여기서 끊는다(나머지도 같은 브로커라 기다려봐야 손해).
                // 실제로는 도달했을 수 있으나(중복 발행 창) 컨슈머 멱등이 흡수한다.
                log.error("아웃박스 발행 ack 타임아웃({}s) eventId={} → 틱 중단, 다음 틱 재시도",
                    sendTimeout.toSeconds(), row.getEventId());
                break;
            } catch (Exception e) {
                log.error("아웃박스 발행 실패 eventId={} topic={} → 다음 틱 재시도",
                    row.getEventId(), row.getTopic(), e);
                // sentIds 미포함 → PENDING 유지
            }
        }
        try {
            if (!sentIds.isEmpty()) {
                // 짧은 벌크 UPDATE 하나로 마킹 — DB 커넥션은 이 순간에만 점유된다(리뷰 M2).
                outboxRepository.markSent(sentIds, LocalDateTime.now(clock));
            }
            log.info("아웃박스 발행 {}건 (대상 {}건)", sentIds.size(), pending.size());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
