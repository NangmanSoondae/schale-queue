package com.schale.queue.core.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schale.queue.core.domain.order.Order;
import com.schale.queue.core.domain.order.OrderItem;
import com.schale.queue.core.domain.order.OrderStatus;
import com.schale.queue.core.domain.order.event.OrderCompletedEvent;
import com.schale.queue.core.domain.order.repository.OrderItemRepository;
import com.schale.queue.core.domain.order.repository.OrderRepository;
import com.schale.queue.core.domain.order.repository.PurchaseSlotRepository;
import com.schale.queue.core.domain.outbox.EventOutbox;
import com.schale.queue.core.domain.outbox.OutboxStatus;
import com.schale.queue.core.domain.outbox.repository.EventOutboxRepository;
import com.schale.queue.core.domain.payment.repository.PaymentRepository;
import com.schale.queue.core.domain.stock.StockService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link PaymentService} 단위 테스트 — 확정/만료 전이와 멱등 가드, 그리고 아웃박스 기록(ADR-007).
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final Long ORDER_ID = 100L;
    private static final Long MEMBER_ID = 42L;
    private static final Long GOODS_ID = 1L;
    private static final int QTY = 2;

    @Mock private PaymentRepository paymentRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private StockService stockService;
    @Mock private PurchaseSlotRepository purchaseSlotRepository;
    @Mock private EventOutboxRepository outboxRepository;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks private PaymentService paymentService;

    private static Payment payment(PaymentStatus status) {
        return Payment.builder().orderId(ORDER_ID).amount(38_000L).status(status).build();
    }

    private static Order order() {
        return Order.builder().memberId(MEMBER_ID).orderStatus(OrderStatus.PENDING).totalAmount(38_000L).build();
    }

    private static OrderItem item() {
        return OrderItem.builder().orderId(ORDER_ID).goodsId(GOODS_ID).quantity(QTY).orderPrice(19_000L).build();
    }

    @Test
    @DisplayName("확정: READY 결제를 PAID 로, 재고 reserved→sold(confirm), 주문 COMPLETED, 아웃박스 적재")
    void confirm_marks_paid_and_writes_outbox() throws Exception {
        Payment payment = payment(PaymentStatus.READY);
        Order order = order();
        given(paymentRepository.findByOrderIdWithPessimisticLock(ORDER_ID)).willReturn(Optional.of(payment));
        given(orderItemRepository.findByOrderId(ORDER_ID)).willReturn(List.of(item()));
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
        given(objectMapper.writeValueAsString(any(OrderCompletedEvent.class)))
            .willReturn("{\"orderId\":100}");

        paymentService.confirm(ORDER_ID, null);

        then(stockService).should().confirm(GOODS_ID, QTY);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getPaymentUid()).isEqualTo("SIM-" + ORDER_ID);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);

        // 주문 변경과 '같은 트랜잭션'에서 아웃박스 행을 적재한다(ADR-007 무유실)
        ArgumentCaptor<EventOutbox> captor = ArgumentCaptor.forClass(EventOutbox.class);
        then(outboxRepository).should().save(captor.capture());
        EventOutbox saved = captor.getValue();
        assertThat(saved.getTopic()).isEqualTo(OrderCompletedEvent.TOPIC);
        assertThat(saved.getAggregateType()).isEqualTo("ORDER");
        assertThat(saved.getAggregateId()).isEqualTo(String.valueOf(ORDER_ID));
        assertThat(saved.getMsgKey()).isEqualTo(String.valueOf(ORDER_ID));
        assertThat(saved.getPayload()).isEqualTo("{\"orderId\":100}");
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getEventId()).isNotBlank();
    }

    @Test
    @DisplayName("확정 멱등: 이미 PAID 면 PaymentNotConfirmableException, 재고·아웃박스 미변경")
    void confirm_rejects_when_not_ready() {
        given(paymentRepository.findByOrderIdWithPessimisticLock(ORDER_ID))
            .willReturn(Optional.of(payment(PaymentStatus.PAID)));

        assertThatThrownBy(() -> paymentService.confirm(ORDER_ID, null))
            .isInstanceOf(PaymentNotConfirmableException.class);
        then(stockService).should(never()).confirm(anyLong(), anyInt());
        then(outboxRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("만료: READY 결제를 EXPIRED 로, 재고 reserved→available(release), 슬롯 반납, 주문 CANCELLED")
    void expireOne_releases_stock_and_slot() {
        Payment payment = payment(PaymentStatus.READY);
        Order order = order();
        given(paymentRepository.findByOrderIdWithPessimisticLock(ORDER_ID)).willReturn(Optional.of(payment));
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
        given(orderItemRepository.findByOrderId(ORDER_ID)).willReturn(List.of(item()));

        paymentService.expireOne(ORDER_ID);

        then(stockService).should().release(GOODS_ID, QTY);
        then(purchaseSlotRepository).should().deleteByMemberIdAndGoodsId(MEMBER_ID, GOODS_ID);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    @DisplayName("만료 멱등: 이미 PAID(경합으로 확정됨)면 아무것도 하지 않는다(no-op)")
    void expireOne_noop_when_not_ready() {
        given(paymentRepository.findByOrderIdWithPessimisticLock(ORDER_ID))
            .willReturn(Optional.of(payment(PaymentStatus.PAID)));

        paymentService.expireOne(ORDER_ID);

        then(stockService).should(never()).release(anyLong(), anyInt());
        then(purchaseSlotRepository).shouldHaveNoInteractions();
    }
}
