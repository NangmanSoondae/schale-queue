package com.schale.queue.core.domain.order;

import com.schale.queue.core.domain.goods.Goods;
import com.schale.queue.core.domain.goods.repository.GoodsRepository;
import com.schale.queue.core.domain.order.repository.OrderItemRepository;
import com.schale.queue.core.domain.order.repository.OrderRepository;
import com.schale.queue.core.domain.order.repository.PurchaseSlotRepository;
import com.schale.queue.core.domain.payment.Payment;
import com.schale.queue.core.domain.payment.PaymentStatus;
import com.schale.queue.core.domain.payment.repository.PaymentRepository;
import com.schale.queue.core.domain.stock.StockService;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 도메인 서비스.
 *
 * <p><b>원자성(Atomicity)이 핵심.</b> 선착순 커머스에서 "재고 차감"과 "주문/결제 생성"은
 * 하나의 운명 공동체다. {@link #createOrder(Long, Long, int)} 전체를 단일
 * {@code @Transactional} 경계로 감싸고, 그 안에서 {@link StockService#decrease(Long, int)}
 * 를 호출한다. {@code StockService} 도 {@code @Transactional} 이지만 기본 전파 정책
 * (REQUIRED)에 따라 <b>같은 트랜잭션에 병합</b>되므로, 이후 주문/결제 저장 단계에서
 * 예외가 발생하면 앞서 차감된 재고까지 함께 롤백되어 정합성이 보존된다.
 *
 * <p><b>결제 도메인 분리(ADR-001).</b> 주문 생성 시 {@code Payment} 를 {@code READY} 상태와
 * 만료 시각({@code timeoutAt})을 가진 채로 함께 생성한다. 외부 PG 연동의 변동성을 주문에서
 * 격리하고, 만료 결제는 향후 {@code module-worker} 가 {@code timeoutAt} 기준으로 배치 정리한다.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    /**
     * 결제 대기 허용 시간. 이 시간이 지나면 워커가 결제를 EXPIRED 로 정리한다.
     *
     * <p>대규모 트래픽에서 PG 연동 지연을 방어하기 위한 값으로, 우선 도메인 기본값으로 둔다.
     * 운영 중 조정이 필요해지면 외부 설정(application.yml)으로 승격한다(§5.4.1).
     */
    private static final Duration PAYMENT_TIMEOUT = Duration.ofMinutes(5);

    private final StockService stockService;
    private final GoodsRepository goodsRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final PurchaseSlotRepository purchaseSlotRepository;

    /**
     * 단일 상품 주문을 생성한다. 재고 차감 → 주문/항목/결제 생성을 하나의 트랜잭션으로 묶는다.
     *
     * <p>흐름: ① 재고를 먼저 안전하게 차감({@code StockService.decrease}, 비관적 락) →
     * ② 가격 스냅샷을 위해 상품 조회 → ③ {@code Order(PENDING)} 저장 →
     * ④ {@code OrderItem} 저장 → ⑤ {@code Payment(READY, timeoutAt=now+5m)} 저장.
     * 어느 단계든 예외가 발생하면 트랜잭션 전체가 롤백되어 재고가 원상 복구된다.
     *
     * @param memberId 주문 회원 ID
     * @param goodsId  주문 상품 ID
     * @param quantity 주문 수량(양수)
     * @return 생성된 주문(PENDING)
     * @throws IllegalArgumentException        재고 또는 상품이 존재하지 않는 경우
     * @throws IllegalStateException           잔여 재고가 부족한 경우(초과 판매 방지)
     * @throws PurchaseLimitExceededException   1인 구매 한도(P-O3)를 초과하거나 활성 주문이 이미 있는 경우
     */
    @Transactional
    public Order createOrder(Long memberId, Long goodsId, int quantity) {
        // ① 상품 조회(단가 스냅샷 + 1인 한도 설정).
        Goods goods = goodsRepository.findById(goodsId)
            .orElseThrow(() -> new IllegalArgumentException("상품이 존재하지 않습니다. goodsId=" + goodsId));

        // ② 1인 구매 한도 수량 검사(P-O3). null=무제한. 재고 차감 전에 빠르게 거른다.
        Integer maxPerMember = goods.getMaxPurchasePerMember();
        if (maxPerMember != null && quantity > maxPerMember) {
            throw new PurchaseLimitExceededException(
                "1인 구매 한도를 초과했습니다. 한도=" + maxPerMember + ", 요청 수량=" + quantity);
        }

        // ③ 재고를 차감한다. 같은 트랜잭션에 병합되어, 이후 단계 실패 시 함께 롤백된다.
        stockService.decrease(goodsId, quantity);

        long unitPrice = goods.getPrice();
        long totalAmount = unitPrice * quantity;

        // ④ 주문(확정된 사실)을 PENDING 으로 저장한다.
        Order order = orderRepository.save(Order.builder()
            .memberId(memberId)
            .orderStatus(OrderStatus.PENDING)
            .totalAmount(totalAmount)
            .build());

        // ⑤ 주문 항목을 단가 스냅샷과 함께 저장한다.
        orderItemRepository.save(OrderItem.builder()
            .orderId(order.getId())
            .goodsId(goodsId)
            .quantity(quantity)
            .orderPrice(unitPrice)
            .build());

        // ⑥ 결제를 READY + 만료 시각과 함께 생성한다(결제 도메인 분리, ADR-001).
        paymentRepository.save(Payment.builder()
            .orderId(order.getId())
            .amount(totalAmount)
            .status(PaymentStatus.READY)
            .timeoutAt(LocalDateTime.now().plus(PAYMENT_TIMEOUT))
            .build());

        // ⑦ 활성 구매 슬롯을 점유한다(P-O3 동시성). (member, goods) 유니크 제약이 같은 회원의 동시
        //    중복 주문을 DB 차원에서 원자적으로 차단한다. 충돌 시 트랜잭션 전체가 롤백돼 재고가 복구된다.
        try {
            purchaseSlotRepository.saveAndFlush(PurchaseSlot.builder()
                .memberId(memberId)
                .goodsId(goodsId)
                .orderId(order.getId())
                .build());
        } catch (DataIntegrityViolationException e) {
            throw new PurchaseLimitExceededException(
                "이미 진행 중인 주문이 있습니다(1인 1주문). memberId=" + memberId + ", goodsId=" + goodsId);
        }

        return order;
    }
}
