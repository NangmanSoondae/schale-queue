package com.schale.queue.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import com.schale.queue.core.domain.goods.Goods;
import com.schale.queue.core.domain.goods.repository.GoodsRepository;
import com.schale.queue.core.domain.order.repository.OrderItemRepository;
import com.schale.queue.core.domain.order.repository.OrderRepository;
import com.schale.queue.core.domain.payment.Payment;
import com.schale.queue.core.domain.payment.PaymentStatus;
import com.schale.queue.core.domain.payment.repository.PaymentRepository;
import com.schale.queue.core.domain.stock.StockService;
import java.time.LocalDateTime;
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

        Goods goods = Goods.builder()
            .name("블루 아카이브 한정판 굿즈")
            .price(unitPrice)
            .openAt(LocalDateTime.now())
            .build();
        given(goodsRepository.findById(goodsId)).willReturn(Optional.of(goods));
        // 저장된 주문에는 PK 가 부여되어 항목/결제가 이를 참조한다(영속성 흉내).
        given(orderRepository.save(any(Order.class))).willAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 100L);
            return saved;
        });

        LocalDateTime before = LocalDateTime.now();

        // when
        Order created = orderService.createOrder(memberId, goodsId, quantity);

        LocalDateTime after = LocalDateTime.now();

        // then — 반환된 주문은 PENDING, 총액 정확
        assertThat(created.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(created.getTotalAmount()).isEqualTo(expectedTotal);

        // then — 재고 차감이 '주문 저장보다 먼저' 수행되었음을 순서로 검증(원자성 흐름의 전제)
        InOrder inOrder = inOrder(stockService, orderRepository, orderItemRepository, paymentRepository);
        inOrder.verify(stockService).decrease(goodsId, quantity);
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

        // then — 결제: READY 상태 + 총액 + 만료 시각(now + 5분) 부여
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        then(paymentRepository).should().save(paymentCaptor.capture());
        Payment savedPayment = paymentCaptor.getValue();
        assertThat(savedPayment.getOrderId()).isEqualTo(100L);
        assertThat(savedPayment.getAmount()).isEqualTo(expectedTotal);
        assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(savedPayment.getPaymentUid()).isNull();
        assertThat(savedPayment.getTimeoutAt())
            .as("결제 만료 시각은 주문 시점 +5분 근방이어야 한다")
            .isBetween(before.plusMinutes(5), after.plusMinutes(5));
    }
}
