package com.schale.queue.worker.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.schale.queue.core.domain.order.event.OrderCancelledEvent;
import com.schale.queue.core.domain.outbox.ProcessedEvent;
import com.schale.queue.core.domain.outbox.repository.ProcessedEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OrderCancellationConsumer} 단위 테스트 — 취소 알림 위임과 멱등 가드(ADR-007).
 */
@ExtendWith(MockitoExtension.class)
class OrderCancellationConsumerTest {

    @Mock private NotifyGatewayClient notifyGatewayClient;
    @Mock private ProcessedEventRepository processedEventRepository;

    @InjectMocks private OrderCancellationConsumer consumer;

    @Test
    @DisplayName("최초 수신: 취소 알림을 발송하고 처리 기록을 남긴다")
    void notifies_and_records_on_first_delivery() {
        OrderCancelledEvent event =
            OrderCancelledEvent.of(1001L, 42L, OrderCancelledEvent.REASON_PAYMENT_EXPIRED);
        given(processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), "notification"))
            .willReturn(false);

        consumer.onOrderCancelled(event);

        then(notifyGatewayClient).should().notifyOrderCancelled(event);
        then(processedEventRepository).should().save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("중복 수신: 이미 처리한 이벤트면 알림을 보내지 않고 건너뛴다(멱등)")
    void skips_duplicate_delivery() {
        OrderCancelledEvent event =
            OrderCancelledEvent.of(1001L, 42L, OrderCancelledEvent.REASON_PAYMENT_EXPIRED);
        given(processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), "notification"))
            .willReturn(true);

        consumer.onOrderCancelled(event);

        then(notifyGatewayClient).should(never()).notifyOrderCancelled(any());
        then(processedEventRepository).should(never()).save(any());
    }
}
