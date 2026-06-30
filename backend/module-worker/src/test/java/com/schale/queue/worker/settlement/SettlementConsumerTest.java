package com.schale.queue.worker.settlement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.schale.queue.core.domain.order.event.OrderCancelledEvent;
import com.schale.queue.core.domain.order.event.OrderCompletedEvent;
import com.schale.queue.core.domain.outbox.ProcessedEvent;
import com.schale.queue.core.domain.outbox.repository.ProcessedEventRepository;
import com.schale.queue.core.domain.settlement.SettlementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link SettlementConsumer} 단위 테스트 — 정산 위임과 멱등 가드(ADR-002 / ADR-007).
 *
 * <p>완료/취소 두 리스너 각각에 대해, 최초 수신 시 {@link SettlementService} 로 위임하고 처리 기록을
 * 남기는지, 중복 수신 시 위임 없이 건너뛰는지(멱등)를 검증한다. 멱등 그룹은 {@code settlement} 로,
 * 알림 컨슈머({@code notification})와 독립적이다.
 */
@ExtendWith(MockitoExtension.class)
class SettlementConsumerTest {

    @Mock private SettlementService settlementService;
    @Mock private ProcessedEventRepository processedEventRepository;

    @InjectMocks private SettlementConsumer consumer;

    @Test
    @DisplayName("주문완료 최초 수신: 정산을 적재하고 처리 기록을 남긴다")
    void settles_and_records_on_first_completed() {
        OrderCompletedEvent event = OrderCompletedEvent.of(1001L, 42L, 100_000L);
        given(processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), "settlement"))
            .willReturn(false);

        consumer.onOrderCompleted(event);

        then(settlementService).should().settle(event);
        then(processedEventRepository).should().save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("주문완료 중복 수신: 이미 처리한 이벤트면 정산하지 않고 건너뛴다(멱등)")
    void skips_duplicate_completed() {
        OrderCompletedEvent event = OrderCompletedEvent.of(1001L, 42L, 100_000L);
        given(processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), "settlement"))
            .willReturn(true);

        consumer.onOrderCompleted(event);

        then(settlementService).should(never()).settle(any());
        then(processedEventRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("주문취소 최초 수신: 정산을 반제하고 처리 기록을 남긴다")
    void reverses_and_records_on_first_cancelled() {
        OrderCancelledEvent event =
            OrderCancelledEvent.of(1001L, 42L, OrderCancelledEvent.REASON_PAYMENT_EXPIRED);
        given(processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), "settlement"))
            .willReturn(false);

        consumer.onOrderCancelled(event);

        then(settlementService).should().reverse(event);
        then(processedEventRepository).should().save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("주문취소 중복 수신: 이미 처리한 이벤트면 반제하지 않고 건너뛴다(멱등)")
    void skips_duplicate_cancelled() {
        OrderCancelledEvent event =
            OrderCancelledEvent.of(1001L, 42L, OrderCancelledEvent.REASON_PAYMENT_EXPIRED);
        given(processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), "settlement"))
            .willReturn(true);

        consumer.onOrderCancelled(event);

        then(settlementService).should(never()).reverse(any());
        then(processedEventRepository).should(never()).save(any());
    }
}
