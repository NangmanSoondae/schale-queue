package com.schale.queue.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import com.schale.queue.core.domain.NotFoundException;
import com.schale.queue.core.domain.goods.Goods;
import com.schale.queue.core.domain.goods.SaleNotOpenException;
import com.schale.queue.core.domain.goods.repository.GoodsRepository;
import com.schale.queue.core.domain.order.repository.OrderItemRepository;
import com.schale.queue.core.domain.order.repository.OrderRepository;
import com.schale.queue.core.domain.order.repository.PurchaseSlotRepository;
import com.schale.queue.core.domain.payment.Payment;
import com.schale.queue.core.domain.payment.PaymentStatus;
import com.schale.queue.core.domain.payment.repository.PaymentRepository;
import com.schale.queue.core.domain.stock.StockService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link OrderService} 단위 테스트 (BDDMockito + AssertJ).
 *
 * <p>DB 없이 협력 객체를 mock 으로 대체하여, 주문 생성의 <b>오케스트레이션</b>이 설계대로
 * 흐르는지 격리 검증한다: (1) 재고를 가장 먼저 차감하는지, (2) 주문/항목/결제를 올바른
 * 값으로 저장하는지, (3) 결제가 {@code READY} + 만료 시각을 갖고 생성되는지.
 *
 * <p>트랜잭션 롤백(원자성) 자체는 mock 으로 증명할 수 없으므로, 실제 DB·트랜잭션을 사용하는
 * {@link OrderIntegrationTest} 에서 별도로 증명한다. (재고 동시성 검증의 단위/통합 분리와 동일한 전략)
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private StockService stockService;

    @Mock
    private GoodsRepository goodsRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PurchaseSlotRepository purchaseSlotRepository;

    // 결제 만료 시각 검증을 결정적으로 하기 위한 고정 Clock(UTC). 만료 검사와 동일 출처(troubleshooting No.10).
    private static final Instant FIXED_INSTANT = Instant.parse("2026-06-29T00:00:00Z");

    @Mock
    private Clock clock;

    @InjectMocks
    private OrderService orderService;

    @Test
    @DisplayName("주문 생성 시 재고를 먼저 차감하고 주문·항목·결제(READY)를 차례로 저장한다")
    void createOrder_decreases_stock_first_then_persists_order_item_payment() {
        // given — 단가 19,000원 상품, 주문 수량 3개 (총액 57,000원)
        long memberId = 1L;
        long goodsId = 10L;
        int quantity = 3;
        long unitPrice = 19_000L;
        long expectedTotal = unitPrice * quantity;

        // openAt 은 고정 Clock 기준 과거(판매 중), 한도는 수량보다 크게 명시(빌더 기본값이 1 이 됨 — M6)
        Goods goods = Goods.builder()
            .name("블루 아카이브 한정판 굿즈")
            .price(unitPrice)
            .openAt(LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC).minusMinutes(1))
            .maxPurchasePerMember(5)
            .build();
        given(goodsRepository.findById(goodsId)).willReturn(Optional.of(goods));
        // 저장된 주문에는 PK 가 부여되어 항목/결제가 이를 참조한다(영속성 흉내).
        given(orderRepository.save(any(Order.class))).willAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 100L);
            return saved;
        });
        // 고정 Clock 으로 만료 시각을 결정적으로 검증한다(now() 비결정성 제거).
        given(clock.instant()).willReturn(FIXED_INSTANT);
        given(clock.getZone()).willReturn(ZoneOffset.UTC);
        LocalDateTime fixedNow = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

        // when
        Order created = orderService.createOrder(memberId, goodsId, quantity);

        // then — 반환된 주문은 PENDING, 총액 정확
        assertThat(created.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(created.getTotalAmount()).isEqualTo(expectedTotal);

        // then — 재고 예약이 '주문 저장보다 먼저' 수행되었음을 순서로 검증(원자성 흐름의 전제)
        InOrder inOrder = inOrder(stockService, orderRepository, orderItemRepository, paymentRepository);
        inOrder.verify(stockService).reserve(goodsId, quantity);
        inOrder.verify(orderRepository).save(any(Order.class));
        inOrder.verify(orderItemRepository).save(any(OrderItem.class));
        inOrder.verify(paymentRepository).save(any(Payment.class));

        // then — 주문 항목: 주문 PK 참조 + 단가 스냅샷 보존
        ArgumentCaptor<OrderItem> itemCaptor = ArgumentCaptor.forClass(OrderItem.class);
        then(orderItemRepository).should().save(itemCaptor.capture());
        OrderItem savedItem = itemCaptor.getValue();
        assertThat(savedItem.getOrderId()).isEqualTo(100L);
        assertThat(savedItem.getGoodsId()).isEqualTo(goodsId);
        assertThat(savedItem.getQuantity()).isEqualTo(quantity);
        assertThat(savedItem.getOrderPrice()).isEqualTo(unitPrice);

        // then — 결제: READY 상태 + 총액 + 만료 시각(now + 상품별 타임아웃, 미지정 기본 10분 — P-O2) 부여
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        then(paymentRepository).should().save(paymentCaptor.capture());
        Payment savedPayment = paymentCaptor.getValue();
        assertThat(savedPayment.getOrderId()).isEqualTo(100L);
        assertThat(savedPayment.getAmount()).isEqualTo(expectedTotal);
        assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(savedPayment.getPaymentUid()).isNull();
        assertThat(savedPayment.getTimeoutAt())
            .as("결제 만료 시각은 고정 Clock 기준 +10분(P-O2 기본)이어야 한다")
            .isEqualTo(fixedNow.plusMinutes(10));
    }

    @Test
    @DisplayName("상품별 결제 타임아웃이 지정되면 만료 시각에 그 값이 쓰인다(P-O2 — 리뷰 잔여 드리프트 해소)")
    void createOrder_uses_goods_specific_payment_timeout() {
        // given — 결제창 15분 상품
        long goodsId = 10L;
        Goods goods = Goods.builder()
            .name("한정 굿즈")
            .price(19_000L)
            .openAt(LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC).minusMinutes(1))
            .maxPurchasePerMember(5)
            .paymentTimeoutMinutes(15)
            .build();
        given(goodsRepository.findById(goodsId)).willReturn(Optional.of(goods));
        given(clock.instant()).willReturn(FIXED_INSTANT);
        given(clock.getZone()).willReturn(ZoneOffset.UTC);
        given(orderRepository.save(any(Order.class))).willAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 102L);
            return saved;
        });

        // when
        orderService.createOrder(1L, goodsId, 1);

        // then — timeoutAt = now + 15분
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        then(paymentRepository).should().save(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getTimeoutAt())
            .isEqualTo(LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC).plusMinutes(15));
    }

    @Test
    @DisplayName("결제 타임아웃 허용 범위(1~30분)를 벗어난 상품 생성은 거부된다(P-O2)")
    void goods_rejects_out_of_range_payment_timeout() {
        assertThatThrownBy(() -> Goods.builder()
            .name("한정 굿즈").price(19_000L)
            .openAt(LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC))
            .paymentTimeoutMinutes(31)
            .build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("주문 수량이 1인 구매 한도를 넘으면 재고 차감 전에 한도 초과로 거부한다(P-O3)")
    void createOrder_rejects_when_quantity_exceeds_purchase_limit() {
        // given — 1인 한도 2개 상품(판매 중)에 3개 주문 시도
        long goodsId = 10L;
        Goods goods = Goods.builder()
            .name("한정 굿즈")
            .price(19_000L)
            .openAt(LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC).minusMinutes(1))
            .maxPurchasePerMember(2)
            .build();
        given(goodsRepository.findById(goodsId)).willReturn(Optional.of(goods));
        given(clock.instant()).willReturn(FIXED_INSTANT);
        given(clock.getZone()).willReturn(ZoneOffset.UTC);

        // when & then — 한도 초과 예외, 재고는 손대지 않는다(fail-fast)
        assertThatThrownBy(() -> orderService.createOrder(1L, goodsId, 3))
            .isInstanceOf(PurchaseLimitExceededException.class);
        then(stockService).should(never()).reserve(anyLong(), anyInt());
        then(orderRepository).should(never()).save(any(Order.class));
    }

    @Test
    @DisplayName("기주문 수량과 합쳐 한도를 넘으면 거부한다(리뷰 M7 — 누적 기준)")
    void createOrder_rejects_when_cumulative_quantity_exceeds_limit() {
        // given — 한도 2개 상품을 이미 1개 보유(유효 주문), 2개 추가 주문 시도
        long memberId = 1L;
        long goodsId = 10L;
        Goods goods = Goods.builder()
            .name("한정 굿즈")
            .price(19_000L)
            .openAt(LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC).minusMinutes(1))
            .maxPurchasePerMember(2)
            .build();
        given(goodsRepository.findById(goodsId)).willReturn(Optional.of(goods));
        given(clock.instant()).willReturn(FIXED_INSTANT);
        given(clock.getZone()).willReturn(ZoneOffset.UTC);
        given(orderItemRepository.sumActiveQuantityByMemberIdAndGoodsId(memberId, goodsId)).willReturn(1L);

        // when & then — 1(기주문) + 2(요청) > 2(한도) → 거부, 재고 무접촉
        assertThatThrownBy(() -> orderService.createOrder(memberId, goodsId, 2))
            .isInstanceOf(PurchaseLimitExceededException.class);
        then(stockService).should(never()).reserve(anyLong(), anyInt());
    }

    @Test
    @DisplayName("기주문이 있어도 잔여 한도 내 재주문은 허용된다(리뷰 M7 — 확정 후 재구매)")
    void createOrder_allows_reorder_within_remaining_limit() {
        // given — 한도 2개 상품을 이미 1개 보유, 1개 추가 주문(잔여 한도 내)
        long memberId = 1L;
        long goodsId = 10L;
        Goods goods = Goods.builder()
            .name("한정 굿즈")
            .price(19_000L)
            .openAt(LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC).minusMinutes(1))
            .maxPurchasePerMember(2)
            .build();
        given(goodsRepository.findById(goodsId)).willReturn(Optional.of(goods));
        given(clock.instant()).willReturn(FIXED_INSTANT);
        given(clock.getZone()).willReturn(ZoneOffset.UTC);
        given(orderItemRepository.sumActiveQuantityByMemberIdAndGoodsId(memberId, goodsId)).willReturn(1L);
        given(orderRepository.save(any(Order.class))).willAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 101L);
            return saved;
        });

        // when
        Order created = orderService.createOrder(memberId, goodsId, 1);

        // then — 1(기주문) + 1(요청) = 2 ≤ 한도 → 정상 진행
        assertThat(created.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        then(stockService).should().reserve(goodsId, 1);
    }

    @Test
    @DisplayName("판매 시작(openAt) 전 주문은 SaleNotOpenException 으로 거부한다(UC-02, 리뷰 M5)")
    void createOrder_rejects_before_sale_opens() {
        // given — openAt 이 고정 Clock 기준 1분 뒤(아직 판매 전)
        long goodsId = 10L;
        Goods goods = Goods.builder()
            .name("한정 굿즈")
            .price(19_000L)
            .openAt(LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC).plusMinutes(1))
            .build();
        given(goodsRepository.findById(goodsId)).willReturn(Optional.of(goods));
        given(clock.instant()).willReturn(FIXED_INSTANT);
        given(clock.getZone()).willReturn(ZoneOffset.UTC);

        // when & then — 재고에 손대기 전에 거부된다(선착순 출발선)
        assertThatThrownBy(() -> orderService.createOrder(1L, goodsId, 1))
            .isInstanceOf(SaleNotOpenException.class);
        then(stockService).should(never()).reserve(anyLong(), anyInt());
        then(orderRepository).should(never()).save(any(Order.class));
    }

    @Test
    @DisplayName("존재하지 않는 상품 주문은 NotFoundException 으로 거부한다(404 매핑, 리뷰 M3)")
    void createOrder_rejects_unknown_goods() {
        given(goodsRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.createOrder(1L, 999L, 1))
            .isInstanceOf(NotFoundException.class);
        then(stockService).should(never()).reserve(anyLong(), anyInt());
    }

    @Test
    @DisplayName("한도 미지정 상품은 기본 1(P-O3) — 2개 주문은 거부된다(리뷰 M6)")
    void createOrder_defaults_purchase_limit_to_one() {
        // given — maxPurchasePerMember 미지정 (과거엔 null=무제한으로 저장되던 결함)
        long goodsId = 10L;
        Goods goods = Goods.builder()
            .name("한정 굿즈")
            .price(19_000L)
            .openAt(LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC).minusMinutes(1))
            .build();
        given(goodsRepository.findById(goodsId)).willReturn(Optional.of(goods));
        given(clock.instant()).willReturn(FIXED_INSTANT);
        given(clock.getZone()).willReturn(ZoneOffset.UTC);

        // when & then
        assertThatThrownBy(() -> orderService.createOrder(1L, goodsId, 2))
            .isInstanceOf(PurchaseLimitExceededException.class);
        then(stockService).should(never()).reserve(anyLong(), anyInt());
    }
}
