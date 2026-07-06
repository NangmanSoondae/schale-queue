package com.schale.queue.worker.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.schale.queue.core.domain.order.event.OrderCompletedEvent;
import com.schale.queue.core.domain.outbox.EventOutbox;
import com.schale.queue.core.domain.outbox.OutboxStatus;
import com.schale.queue.core.domain.outbox.repository.EventOutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link OutboxRelay} 단위 테스트(ADR-007). 발행 성공분만 벌크로 SENT 마킹하고, 실패/타임아웃 시
 * PENDING 을 유지해 다음 틱 재시도가 되도록(at-least-once) 검증한다(리뷰 M2 — 트랜잭션 밖 발행 +
 * ack 타임아웃 + 짧은 벌크 마킹). 실제 브로커 없이 KafkaTemplate 을 목으로 대체한다.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final Clock FIXED =
        Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime FIXED_NOW = LocalDateTime.now(FIXED);

    @Mock private EventOutboxRepository outboxRepository;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxRelay relay;

    private static EventOutbox row(long id) {
        EventOutbox row = EventOutbox.pending("evt-" + id, "ORDER", "100",
            OrderCompletedEvent.TOPIC, "100", "{\"orderId\":100}");
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(outboxRepository, kafkaTemplate, FIXED,
            new com.schale.queue.worker.health.WorkerLiveness(FIXED));
        // @Value 필드는 단위 테스트에서 주입되지 않으므로 직접 세팅(운영 기본 10s, 테스트는 짧게).
        ReflectionTestUtils.setField(relay, "sendTimeout", Duration.ofMillis(200));
    }

    @Test
    @DisplayName("발행 성공: broker ack 를 받은 행만 모아 벌크 UPDATE 로 SENT 마킹한다")
    void marks_sent_in_bulk_on_successful_publish() {
        given(outboxRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING))
            .willReturn(List.of(row(1L), row(2L)));
        CompletableFuture<SendResult<String, String>> ack = CompletableFuture.completedFuture(null);
        given(kafkaTemplate.send(OrderCompletedEvent.TOPIC, "100", "{\"orderId\":100}")).willReturn(ack);

        relay.relay();

        then(outboxRepository).should().markSent(List.of(1L, 2L), FIXED_NOW);
    }

    @Test
    @DisplayName("발행 실패: 전송 실패 행은 마킹하지 않아 PENDING 유지 → 다음 틱 재시도(at-least-once)")
    void keeps_pending_on_publish_failure() {
        given(outboxRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING))
            .willReturn(List.of(row(1L)));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        given(kafkaTemplate.send(OrderCompletedEvent.TOPIC, "100", "{\"orderId\":100}")).willReturn(failed);

        relay.relay();

        then(outboxRepository).should(never()).markSent(anyList(), any());
    }

    @Test
    @DisplayName("ack 타임아웃: 무기한 대기 대신 틱을 끊고 PENDING 유지(리뷰 M2 — 커넥션/스레드 잠식 차단)")
    void breaks_tick_on_ack_timeout() {
        given(outboxRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING))
            .willReturn(List.of(row(1L), row(2L)));
        // 완료되지 않는 future — get(timeout) 이 TimeoutException 으로 빠져나와야 한다.
        given(kafkaTemplate.send(OrderCompletedEvent.TOPIC, "100", "{\"orderId\":100}"))
            .willReturn(new CompletableFuture<>());

        relay.relay();

        // 첫 행 타임아웃에서 틱을 중단하므로 send 는 1회만, 마킹은 없다.
        then(kafkaTemplate).should().send(OrderCompletedEvent.TOPIC, "100", "{\"orderId\":100}");
        then(outboxRepository).should(never()).markSent(anyList(), any());
    }

    @Test
    @DisplayName("부분 성공 후 타임아웃으로 틱이 끊겨도 '이미 ack 받은 행'은 마킹된다(리뷰2 L-7 커버리지)")
    void marks_already_acked_rows_when_tick_breaks_on_timeout() {
        EventOutbox ok = row(1L);
        EventOutbox stuck = row(2L);
        ReflectionTestUtils.setField(stuck, "payload", "{\"orderId\":200}");
        given(outboxRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING))
            .willReturn(List.of(ok, stuck));
        given(kafkaTemplate.send(OrderCompletedEvent.TOPIC, "100", "{\"orderId\":100}"))
            .willReturn(CompletableFuture.completedFuture(null));
        given(kafkaTemplate.send(OrderCompletedEvent.TOPIC, "100", "{\"orderId\":200}"))
            .willReturn(new CompletableFuture<>());   // 미완료 → get(timeout) 초과

        relay.relay();

        then(outboxRepository).should().markSent(List.of(1L), FIXED_NOW);
    }

    @Test
    @DisplayName("인터럽트로 중단돼도 기발행분은 마킹되고, 플래그는 마킹 후 복원된다(리뷰2 M-11)")
    void marks_acked_rows_before_restoring_interrupt_flag() {
        EventOutbox ok = row(1L);
        EventOutbox pendingRow = row(2L);
        ReflectionTestUtils.setField(pendingRow, "payload", "{\"orderId\":200}");
        given(outboxRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING))
            .willReturn(List.of(ok, pendingRow));
        // 완료된 future 는 인터럽트 상태에서도 즉시 반환하고, 미완료 future 의 get 은 대기 진입 시
        // InterruptedException 을 던진다 — "1건 ack 후 셧다운 인터럽트" 상황의 재현.
        given(kafkaTemplate.send(OrderCompletedEvent.TOPIC, "100", "{\"orderId\":100}"))
            .willReturn(CompletableFuture.completedFuture(null));
        given(kafkaTemplate.send(OrderCompletedEvent.TOPIC, "100", "{\"orderId\":200}"))
            .willReturn(new CompletableFuture<>());

        try {
            Thread.currentThread().interrupt();
            relay.relay();

            // 인터럽트 플래그가 markSent '이후' 복원됐다면 마킹은 성공했어야 한다.
            then(outboxRepository).should().markSent(List.of(1L), FIXED_NOW);
            org.assertj.core.api.Assertions.assertThat(Thread.currentThread().isInterrupted())
                .as("인터럽트 플래그는 보존되어야 한다(호출자/스케줄러 종료 신호)").isTrue();
        } finally {
            // 다른 테스트 오염 방지
            boolean cleared = Thread.interrupted();
            org.assertj.core.api.Assertions.assertThat(cleared).isTrue();
        }
    }

    @Test
    @DisplayName("PENDING 이 없으면 Kafka 전송을 시도하지 않는다")
    void noop_when_no_pending() {
        given(outboxRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING))
            .willReturn(List.of());

        relay.relay();

        then(kafkaTemplate).shouldHaveNoInteractions();
    }
}
