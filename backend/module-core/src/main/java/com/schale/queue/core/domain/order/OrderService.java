package com.schale.queue.core.domain.order;

import com.schale.queue.core.domain.NotFoundException;
import com.schale.queue.core.domain.goods.Goods;
import com.schale.queue.core.domain.goods.SaleNotOpenException;
import com.schale.queue.core.domain.goods.repository.GoodsRepository;
import com.schale.queue.core.domain.stock.InsufficientStockException;
import com.schale.queue.core.domain.order.repository.OrderItemRepository;
import com.schale.queue.core.domain.order.repository.OrderRepository;
import com.schale.queue.core.domain.order.repository.PurchaseSlotRepository;
import com.schale.queue.core.domain.payment.Payment;
import com.schale.queue.core.domain.payment.PaymentStatus;
import com.schale.queue.core.domain.payment.repository.PaymentRepository;
import com.schale.queue.core.domain.stock.StockService;
import java.time.Clock;
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
    // P-O2: 결제창 수명은 상품별 설정(Goods.paymentTimeoutMinutes, 기본 10분·허용 1~30).
    // 과거의 5분 고정 상수는 문서-코드 드리프트였다(리뷰 잔여 항목) — Goods.paymentTimeout() 로 대체.

    private final StockService stockService;
    private final GoodsRepository goodsRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final PurchaseSlotRepository purchaseSlotRepository;
    private final Clock clock;

    /**
     * 단일 상품 주문을 생성한다. 재고 차감 → 주문/항목/결제 생성을 하나의 트랜잭션으로 묶는다.
     *
     * <p>흐름: ① 재고를 먼저 안전하게 예약({@code StockService.reserve}, 비관적 락, P-S2) →
     * ② 가격 스냅샷을 위해 상품 조회 → ③ {@code Order(PENDING)} 저장 →
     * ④ {@code OrderItem} 저장 → ⑤ {@code Payment(READY, timeoutAt=now+5m)} 저장.
     * 어느 단계든 예외가 발생하면 트랜잭션 전체가 롤백되어 재고가 원상 복구된다.
     *
     * @param memberId 주문 회원 ID
     * @param goodsId  주문 상품 ID
     * @param quantity 주문 수량(양수)
     * @return 생성된 주문(PENDING)
     * @throws NotFoundException                재고 또는 상품이 존재하지 않는 경우
     * @throws SaleNotOpenException             판매 시작(openAt) 이전의 주문 시도(UC-02)
     * @throws InsufficientStockException       잔여 재고가 부족한 경우(초과 판매 방지)
     * @throws PurchaseLimitExceededException   1인 구매 한도(P-O3)를 초과하거나 활성 주문이 이미 있는 경우
     */
    @Transactional
    public Order createOrder(Long memberId, Long goodsId, int quantity) {
        // ① 상품 조회(단가 스냅샷 + 1인 한도 설정).
        Goods goods = goodsRepository.findById(goodsId)
            .orElseThrow(() -> new NotFoundException("상품이 존재하지 않습니다. goodsId=" + goodsId));

        // ①-1 판매 시작 게이트(UC-02, 리뷰 M5). 대기열 진입(QueueController)에서 걸렀어도, 진입 시점과
        //     주문 시점이 다르고 진입 게이트는 우회 가능(API 직접 호출)하므로 확정 지점에서 재검사한다.
        if (LocalDateTime.now(clock).isBefore(goods.getOpenAt())) {
            throw new SaleNotOpenException(
                "판매 시작 전입니다. goodsId=" + goodsId + ", openAt=" + goods.getOpenAt());
        }

        // ② 1인 구매 한도 '선검사'(P-O3). null=무제한(레거시 행). 재고 차감 전에 빠르게 거른다.
        //    '누적' 기준(리뷰 M7): 이번 요청 + 기존 유효 주문(취소 제외) 수량의 합이 한도를 넘으면 거부.
        //    ⚠️ 이 검사는 REPEATABLE READ 스냅샷 읽기라 최종 권위가 아니다 — 내 스냅샷 이후 커밋된
        //    타 주문(+confirm 의 슬롯 반납)은 못 본다(리뷰2 H-1). 최종 판정은 ⑧의 잠금 재검사가 한다.
        //    여기서는 명백한 초과를 재고 락 진입 전에 싸게 거르는 fail-fast 역할만 남긴다.
        Integer maxPerMember = goods.getMaxPurchasePerMember();
        if (maxPerMember != null) {
            long alreadyOrdered = orderItemRepository.sumActiveQuantityByMemberIdAndGoodsId(memberId, goodsId);
            if (alreadyOrdered + quantity > maxPerMember) {
                throw new PurchaseLimitExceededException(
                    "1인 구매 한도를 초과했습니다. 한도=" + maxPerMember
                        + ", 기주문(유효)=" + alreadyOrdered + ", 요청 수량=" + quantity);
            }
        }

        // ③ 재고를 예약한다(P-S2: available-- reserved++). 같은 트랜잭션에 병합되어, 이후 단계 실패 시 함께 롤백된다.
        stockService.reserve(goodsId, quantity);

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
            // 만료 검사(PaymentExpiryWorker)와 '같은 시간 출처(Clock)'를 써야 한다. 과거엔 여기서
            // 시스템 기본 존의 now() 를, 만료 검사는 UTC clock 을 써 타임존이 어긋나 만료가 ~9h 늦게
            // 발동했다(troubleshooting No.10). Clock 으로 통일해 생성·검사를 일관되게 맞춘다.
            .timeoutAt(LocalDateTime.now(clock).plus(goods.paymentTimeout()))
            .build());

        // ⑦ 활성 구매 슬롯을 점유한다(P-O3 동시성). (member, goods) 유니크 제약이 같은 회원의 동시
        //    중복 주문을 DB 차원에서 원자적으로 차단한다. 충돌 시 트랜잭션 전체가 롤백돼 재고가 복구된다.
        //    이 INSERT 가 같은 (member, goods) 의 활성 생성 경로를 직렬화하는 지점이기도 하다(⑧의 전제).
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

        // ⑧ 누적 한도 '최종 재검사' — 잠금 읽기(FOR UPDATE = 최신 커밋 기준, 리뷰2 H-1).
        //    ②의 스냅샷 SUM 은 "내 스냅샷 이후 커밋 + confirm 슬롯 반납" 인터리빙(재고 락 대기 중
        //    타 주문이 생성·확정되는 경우)을 못 본다. 슬롯 직렬화(⑦) 이후 최신 커밋본을 잠금 읽기로
        //    재합산해, 초과면 전체 롤백한다(재고·슬롯 원복). 자기 주문 행이 포함되므로 비교식은 초과 여부.
        //    saveAndFlush(⑦)가 컨텍스트 전체를 flush 했고 네이티브 쿼리 전 auto-flush 도 걸리므로
        //    자기 행 누락은 없다.
        if (maxPerMember != null) {
            Long lockedSum = orderItemRepository.sumActiveQuantityForUpdate(memberId, goodsId);
            long committedTotal = lockedSum != null ? lockedSum : 0L;   // coalesce 라 실 DB 에선 null 불가(방어)
            if (committedTotal > maxPerMember) {
                throw new PurchaseLimitExceededException(
                    "1인 구매 한도를 초과했습니다(동시 주문 감지). 한도=" + maxPerMember
                        + ", 유효 합계=" + committedTotal);
            }
        }

        return order;
    }
}
