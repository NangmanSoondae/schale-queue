package com.schale.queue.core.domain.payment;

import com.schale.queue.core.domain.order.Order;
import com.schale.queue.core.domain.order.OrderItem;
import com.schale.queue.core.domain.order.OrderStatus;
import com.schale.queue.core.domain.order.event.OrderCompletedEvent;
import com.schale.queue.core.domain.order.repository.OrderItemRepository;
import com.schale.queue.core.domain.order.repository.OrderRepository;
import com.schale.queue.core.domain.order.repository.PurchaseSlotRepository;
import com.schale.queue.core.domain.payment.repository.PaymentRepository;
import com.schale.queue.core.domain.stock.StockService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 생애주기 도메인 서비스(UC-06 확정 / UC-07 만료 해제). 예약 재고(P-S2)와 1인 한도 슬롯(P-O3)의
 * 후반 전이를 완성한다.
 *
 * <p><b>경합 직렬화 + 멱등.</b> 결제 확정({@link #confirm})과 만료 해제({@link #expireOne})는 같은 결제를
 * 동시에 건드릴 수 있다. 둘 다 결제 행을 <b>비관적 락</b>으로 먼저 잡고({@code findByOrderIdWithPessimisticLock}),
 * 락 획득 후 {@code status==READY} 를 재확인한다. 먼저 락을 잡은 쪽만 전이하고, 나중 쪽은 상태가 바뀐 것을
 * 보고 확정은 거부·만료는 무시(no-op)한다 → 중복 호출/경합에도 카운터가 이중 이동하지 않는다(P-P2).
 * 락 순서는 항상 <b>결제 → 재고</b> 라 데드락이 없다.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockService stockService;
    private final PurchaseSlotRepository purchaseSlotRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 결제 확정(UC-06): {@code READY→PAID}, 재고 {@code reserved→sold}(P-S2), 주문 {@code COMPLETED}.
     *
     * @throws IllegalArgumentException        결제/주문이 존재하지 않는 경우
     * @throws PaymentNotConfirmableException  결제가 READY 가 아닌 경우(이미 확정/만료 — 멱등)
     */
    @Transactional
    public void confirm(Long orderId, String approvalUid) {
        Payment payment = paymentRepository.findByOrderIdWithPessimisticLock(orderId)
            .orElseThrow(() -> new IllegalArgumentException("결제가 존재하지 않습니다. orderId=" + orderId));
        if (payment.getStatus() != PaymentStatus.READY) {
            throw new PaymentNotConfirmableException(
                "결제를 확정할 수 없는 상태입니다. status=" + payment.getStatus());
        }

        for (OrderItem item : orderItemRepository.findByOrderId(orderId)) {
            stockService.confirm(item.getGoodsId(), item.getQuantity());   // reserved→sold
        }
        payment.approve(approvalUid != null ? approvalUid : "SIM-" + orderId);
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("주문이 존재하지 않습니다. orderId=" + orderId));
        order.changeStatus(OrderStatus.COMPLETED);

        // 주문 완료 이벤트 발행(ADR-002 후방 파이프라인). core 는 Kafka 비의존 — ApplicationEvent 로 발행하고,
        // api 의 브리지가 트랜잭션 커밋 후 Kafka 로 내보낸다(AFTER_COMMIT). 컨슈머가 알림을 보낸다(UC-08).
        eventPublisher.publishEvent(
            OrderCompletedEvent.of(orderId, order.getMemberId(), order.getTotalAmount()));
    }

    /** 만료 대상 주문 ID 목록(READY + {@code timeoutAt} 경과). 워커가 건별로 {@link #expireOne} 호출한다. */
    @Transactional(readOnly = true)
    public List<Long> findDueOrderIds(LocalDateTime now) {
        return paymentRepository.findByStatusAndTimeoutAtBefore(PaymentStatus.READY, now)
            .stream().map(Payment::getOrderId).toList();
    }

    /**
     * 만료 해제(UC-07/P-S4): {@code READY→EXPIRED}, 재고 {@code reserved→available}, 1인 한도 슬롯 반납,
     * 주문 {@code CANCELLED}. 이미 확정/만료됐으면 아무것도 하지 않는다(멱등).
     */
    @Transactional
    public void expireOne(Long orderId) {
        Payment payment = paymentRepository.findByOrderIdWithPessimisticLock(orderId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.READY) {
            return;   // 경합으로 이미 확정/만료됨 — 멱등 no-op
        }
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("주문이 존재하지 않습니다. orderId=" + orderId));

        for (OrderItem item : orderItemRepository.findByOrderId(orderId)) {
            stockService.release(item.getGoodsId(), item.getQuantity());                 // reserved→available
            purchaseSlotRepository.deleteByMemberIdAndGoodsId(order.getMemberId(), item.getGoodsId()); // 슬롯 반납(P-O3)
        }
        order.changeStatus(OrderStatus.CANCELLED);
        payment.changeStatus(PaymentStatus.EXPIRED);
    }
}
