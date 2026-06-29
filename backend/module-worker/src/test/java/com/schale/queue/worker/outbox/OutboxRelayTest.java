package com.schale.queue.worker.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.schale.queue.core.domain.order.event.OrderCompletedEvent;
import com.schale.queue.core.domain.outbox.EventOutbox;
import com.schale.queue.core.domain.outbox.OutboxStatus;
import com.schale.queue.core.domain.outbox.repository.EventOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * {@link OutboxRelay} 단위 테스트(ADR-007). 발행 성공 시에만 SENT 로 마킹하고, 실패 시 PENDING 을 유지해
 * 다음 틱 재시도가 되도록(at-least-once) 검증한다. 실제 브로커 없이 KafkaTemplate 을 목으로 대체한다.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final Clock FIXED =
        Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);

    @Mock private EventOutboxRepository outboxRepository;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxRelay relay;

    private static EventOutbox row() {
        return EventOutbox.pending("evt-1", "ORDER", "100",
            OrderCompletedEvent.TOPIC, "100", "{\"orderId\":100}");
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        relay = new OutboxRelay(outboxRepository, kafkaTemplate, FIXED);
    }

    @Test
    @DisplayName("발행 성공: PENDING 행을 Kafka 로 보내고 broker ack 후 SENT 로 마킹한다")
    void marks_sent_on_successful_publish() {
        EventOutbox row = row();
        given(outboxRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING))
            .willReturn(List.of(row));
        CompletableFuture<SendResult<String, String>> ack = CompletableFuture.completedFuture(null);
        given(kafkaTemplate.send(OrderCompletedEvent.TOPIC, "100", "{\"orderId\":100}")).willReturn(ack);

        relay.relay();

        then(kafkaTemplate).should().send(OrderCompletedEvent.TOPIC, "100", "{\"orderId\":100}");
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(row.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("발행 실패: 전송이 실패하면 PENDING 을 유지해 다음 틱에 재시도한다(at-least-once)")
    void keeps_pending_on_publish_failure() {
        EventOutbox row = row();
        given(outboxRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING))
            .willReturn(List.of(row));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        given(kafkaTemplate.send(OrderCompletedEvent.TOPIC, "100", "{\"orderId\":100}")).willReturn(failed);

        relay.relay();

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getSentAt()).isNull();
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
