package com.schale.queue.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

import com.schale.queue.core.CoreTestApplication;
import com.schale.queue.core.domain.goods.Goods;
import com.schale.queue.core.domain.goods.repository.GoodsRepository;
import com.schale.queue.core.domain.member.Member;
import com.schale.queue.core.domain.member.Role;
import com.schale.queue.core.domain.member.repository.MemberRepository;
import com.schale.queue.core.domain.order.repository.OrderItemRepository;
import com.schale.queue.core.domain.order.repository.OrderRepository;
import com.schale.queue.core.domain.order.repository.PurchaseSlotRepository;
import com.schale.queue.core.domain.payment.Payment;
import com.schale.queue.core.domain.payment.PaymentStatus;
import com.schale.queue.core.domain.payment.repository.PaymentRepository;
import com.schale.queue.core.domain.stock.Stock;
import com.schale.queue.core.domain.stock.repository.StockRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 주문 생성 통합 테스트 — 트랜잭션 원자성(Atomicity) 증명.
 *
 * <p><b>증명 목표</b>:
 * <ol>
 *   <li><b>정상 흐름</b>: 주문(PENDING)·항목·결제(READY, timeoutAt)가 생성되고 재고가 정확히 차감된다.</li>
 *   <li><b>롤백 흐름</b>: 주문 저장 이후 단계(결제 저장)에서 강제 예외가 터지면,
 *       {@code @Transactional} 에 의해 <b>앞서 차감된 재고가 원상 복구</b>되고
 *       주문/결제도 일절 남지 않는다 — "재고 차감"과 "주문/결제 생성"의 운명 공동체성.</li>
 * </ol>
 *
 * <p>롤백은 mock 으로 흉내 낼 수 없는 <b>실제 트랜잭션의 거동</b>이므로, {@code docker-compose}
 * 로 띄운 실제 MariaDB(InnoDB)에 접속하여 검증한다. 인프라가 필요하므로 {@link StockConcurrencyTest}
 * 와 동일하게 {@code RUN_DB_IT=true} 일 때만 실행된다.
 *
 * <p>강제 예외 주입에는 {@link MockitoSpyBean}(Spring Boot 3.4+ 의 {@code @SpyBean} 대체)을 써서
 * {@code PaymentRepository.save} 만 예외를 던지게 한다. 나머지 경로는 실제 빈을 그대로 사용하므로,
 * "주문/항목은 이미 INSERT 된 상태에서 마지막 결제 저장이 실패"하는 현실적인 부분 실패 상황을 재현한다.
 */
@SpringBootTest(classes = CoreTestApplication.class)
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class OrderIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private GoodsRepository goodsRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private PurchaseSlotRepository purchaseSlotRepository;

    // 결제 저장만 강제로 실패시키기 위한 스파이. 나머지 동작은 실제 빈과 동일하다.
    @MockitoSpyBean
    private PaymentRepository paymentRepository;

    // 시간 비교는 서비스와 '같은 시간 출처'(UTC Clock 빈)를 써야 한다. 시스템존 now()(KST)로 기대값을
    // 만들면 UTC 로 저장된 timeoutAt 과 ~9h 어긋나 KST 호스트에서만 실패한다(troubleshooting No.10 부류).
    @Autowired
    private Clock clock;

    private static final long UNIT_PRICE = 19_000L;
    private static final int INITIAL_STOCK = 100;

    private Long memberId;
    private Long goodsId;

    @BeforeEach
    void setUp() {
        // FK 의존성 역순으로 정리 (payment → order_item → orders → stock → goods → member)
        cleanUp();

        Member member = memberRepository.save(Member.builder()
            .email("sensei@schale.gg")
            .password("hashed-password")
            .name("선생")
            .role(Role.USER)
            .build());
        memberId = member.getId();

        Goods goods = goodsRepository.save(Goods.builder()
            .name("블루 아카이브 한정판 굿즈")
            .price(UNIT_PRICE)
            .openAt(LocalDateTime.now(clock))
            .build());
        goodsId = goods.getId();

        stockRepository.save(Stock.builder()
            .goodsId(goodsId)
            .totalQuantity(INITIAL_STOCK)
            .availableQuantity(INITIAL_STOCK)
            .build());
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        // FK 의존성 역순: purchase_slot(→member/goods/orders) 을 먼저 비운다.
        purchaseSlotRepository.deleteAll();
        paymentRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        stockRepository.deleteAll();
        goodsRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("정상 흐름: 주문(PENDING)·항목·결제(READY)가 생성되고 재고가 정확히 차감된다")
    void createOrder_happyPath_persists_all_and_decreases_stock() {
        // given — 재고 100개, 3개 주문 (총액 57,000원)
        int quantity = 3;
        long expectedTotal = UNIT_PRICE * quantity;
        LocalDateTime before = LocalDateTime.now(clock);

        // when
        Order created = orderService.createOrder(memberId, goodsId, quantity);

        // then — 주문: PENDING + 총액
        Order order = orderRepository.findById(created.getId()).orElseThrow();
        assertThat(order.getMemberId()).isEqualTo(memberId);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getTotalAmount()).isEqualTo(expectedTotal);

        // then — 주문 항목: 단가 스냅샷 보존
        var items = orderItemRepository.findByOrderId(order.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getGoodsId()).isEqualTo(goodsId);
        assertThat(items.get(0).getQuantity()).isEqualTo(quantity);
        assertThat(items.get(0).getOrderPrice()).isEqualTo(UNIT_PRICE);

        // then — 결제: READY + 총액 + 만료 시각(미래)
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(payment.getAmount()).isEqualTo(expectedTotal);
        assertThat(payment.getPaymentUid()).isNull();
        assertThat(payment.getTimeoutAt())
            .as("결제 만료 시각은 주문 시점 이후의 미래여야 한다")
            .isAfter(before);

        // then — 재고: 정확히 3개 예약(available-- reserved++), 합계 불변식 성립
        Stock stock = stockRepository.findByGoodsId(goodsId).orElseThrow();
        assertThat(stock.getAvailableQuantity()).isEqualTo(INITIAL_STOCK - quantity);
        assertThat(stock.getReservedQuantity()).isEqualTo(quantity);
        assertThat(stock.getSoldQuantity()).isZero();
    }

    @Test
    @DisplayName("롤백 흐름: 결제 저장 단계에서 예외가 터지면 차감된 재고가 원상 복구되고 주문도 남지 않는다")
    void createOrder_whenPaymentSaveFails_rollsBackStockAndOrder() {
        // given — 결제 저장 직전까지는 정상 진행되다가, 마지막 결제 저장에서 강제 예외
        int quantity = 5;
        willThrow(new RuntimeException("강제 주입: 결제 저장 실패 (PG 연동 장애 가정)"))
            .given(paymentRepository).save(any(Payment.class));

        // when — 주문 생성 시도 → 예외 전파
        assertThatThrownBy(() -> orderService.createOrder(memberId, goodsId, quantity))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("결제 저장 실패");

        // then — 같은 트랜잭션이 롤백되어 재고가 예약 전(가용 100, 예약 0)으로 원상 복구
        Stock stock = stockRepository.findByGoodsId(goodsId).orElseThrow();
        assertThat(stock.getAvailableQuantity())
            .as("결제 실패로 트랜잭션이 롤백되었으므로 재고는 예약되지 않아야 한다")
            .isEqualTo(INITIAL_STOCK);
        assertThat(stock.getReservedQuantity()).as("예약도 롤백되어 0").isZero();

        // then — 주문/항목도 함께 롤백되어 흔적이 남지 않음 (운명 공동체)
        assertThat(orderRepository.findByMemberId(memberId))
            .as("롤백되었으므로 회원의 주문이 단 한 건도 없어야 한다")
            .isEmpty();
        assertThat(orderItemRepository.count()).isZero();
    }
}
