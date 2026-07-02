package com.schale.queue.core.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.schale.queue.core.CoreTestApplication;
import com.schale.queue.core.domain.goods.Goods;
import com.schale.queue.core.domain.goods.repository.GoodsRepository;
import com.schale.queue.core.domain.member.Member;
import com.schale.queue.core.domain.member.Role;
import com.schale.queue.core.domain.member.repository.MemberRepository;
import com.schale.queue.core.domain.order.Order;
import com.schale.queue.core.domain.order.OrderItem;
import com.schale.queue.core.domain.order.OrderStatus;
import com.schale.queue.core.domain.order.PurchaseSlot;
import com.schale.queue.core.domain.order.repository.OrderItemRepository;
import com.schale.queue.core.domain.order.repository.OrderRepository;
import com.schale.queue.core.domain.order.repository.PurchaseSlotRepository;
import com.schale.queue.core.domain.payment.repository.PaymentRepository;
import com.schale.queue.core.domain.stock.Stock;
import com.schale.queue.core.domain.stock.repository.StockRepository;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 결제 확정 ↔ 만료 해제 경합 통합 테스트(UC-06/UC-07, P-P2 멱등).
 *
 * <p><b>증명 목표</b>: 같은 결제를 동시에 확정·만료하려 해도 비관적 락 + 상태 재확인으로 <b>정확히 한
 * 전이만</b> 일어나고, 재고 합계 불변식(P-S1)이 유지된다. 승자에 따라 (PAID·COMPLETED·sold) 또는
 * (EXPIRED·CANCELLED·available 복원·슬롯 반납) 중 하나로 <b>일관되게</b> 귀결된다.
 */
@SpringBootTest(classes = CoreTestApplication.class)
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class PaymentLifecycleConcurrencyTest {

    private static final int TOTAL = 10;
    private static final int QTY = 2;

    @Autowired private PaymentService paymentService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private GoodsRepository goodsRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PurchaseSlotRepository purchaseSlotRepository;

    private Long orderId;
    private Long goodsId;

    @BeforeEach
    void setUp() {
        cleanUp();
        Long memberId = memberRepository.save(Member.builder()
            .email("sensei@schale.gg").password("hashed").name("선생").role(Role.USER).build()).getId();
        goodsId = goodsRepository.save(Goods.builder()
            .name("한정 굿즈").price(19_000L).openAt(LocalDateTime.now()).build()).getId();
        // 이미 QTY 만큼 예약된 상태(주문 생성 직후 = available 8 / reserved 2 / sold 0)
        stockRepository.save(Stock.builder()
            .goodsId(goodsId).totalQuantity(TOTAL).availableQuantity(TOTAL - QTY)
            .reservedQuantity(QTY).soldQuantity(0).build());

        orderId = orderRepository.save(Order.builder()
            .memberId(memberId).orderStatus(OrderStatus.PENDING).totalAmount(19_000L * QTY).build()).getId();
        orderItemRepository.save(OrderItem.builder()
            .orderId(orderId).goodsId(goodsId).quantity(QTY).orderPrice(19_000L).build());
        purchaseSlotRepository.save(PurchaseSlot.builder()
            .memberId(memberId).goodsId(goodsId).orderId(orderId).build());
        paymentRepository.save(Payment.builder()
            .orderId(orderId).amount(19_000L * QTY).status(PaymentStatus.READY)
            .timeoutAt(LocalDateTime.now().minusMinutes(1)).build());
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        purchaseSlotRepository.deleteAll();
        paymentRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        stockRepository.deleteAll();
        goodsRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("확정과 만료를 동시에 시도해도 정확히 하나만 성립하고 재고 불변식이 유지된다")
    void confirm_and_expire_race_resolves_to_exactly_one() throws InterruptedException {
        // when — 확정과 만료를 동시에 발사
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        executor.submit(() -> {
            try {
                start.await();
                paymentService.confirm(orderId, null, null);   // memberId=null → 내부 호출(소유권 검증 생략)
            } catch (Exception ignored) {
                // 만료가 이겼다면 PaymentNotConfirmableException — 정상 경합 결과
            } finally {
                done.countDown();
            }
        });
        executor.submit(() -> {
            try {
                start.await();
                paymentService.expireOne(orderId);
            } catch (Exception ignored) {
                // ignore
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        assertThat(done.await(15, TimeUnit.SECONDS)).as("경합이 15초 내 끝나야 한다").isTrue();
        executor.shutdown();

        // then — 결제는 종결(PAID xor EXPIRED), 예약 0, 합계 불변식 성립
        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        Stock stock = stockRepository.findByGoodsId(goodsId).orElseThrow();
        Order order = orderRepository.findById(orderId).orElseThrow();

        assertThat(payment.getStatus()).as("결제는 종결 상태").isIn(PaymentStatus.PAID, PaymentStatus.EXPIRED);
        assertThat(stock.getReservedQuantity()).as("예약은 0으로 전이됨").isZero();
        assertThat(stock.getAvailableQuantity() + stock.getReservedQuantity() + stock.getSoldQuantity())
            .as("합계 불변식 total=available+reserved+sold").isEqualTo(TOTAL);

        if (payment.getStatus() == PaymentStatus.PAID) {
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
            assertThat(stock.getSoldQuantity()).isEqualTo(QTY);
            assertThat(stock.getAvailableQuantity()).isEqualTo(TOTAL - QTY);
            assertThat(purchaseSlotRepository.count()).as("확정 시 슬롯 유지").isEqualTo(1);
        } else {
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(stock.getSoldQuantity()).isZero();
            assertThat(stock.getAvailableQuantity()).isEqualTo(TOTAL);
            assertThat(purchaseSlotRepository.count()).as("만료 시 슬롯 반납").isZero();
        }
    }
}
