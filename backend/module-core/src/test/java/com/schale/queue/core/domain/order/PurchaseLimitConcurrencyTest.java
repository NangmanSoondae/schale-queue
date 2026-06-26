package com.schale.queue.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.schale.queue.core.CoreTestApplication;
import com.schale.queue.core.domain.goods.Goods;
import com.schale.queue.core.domain.goods.repository.GoodsRepository;
import com.schale.queue.core.domain.member.Member;
import com.schale.queue.core.domain.member.Role;
import com.schale.queue.core.domain.member.repository.MemberRepository;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 1인 구매 한도(P-O3) 동시성 통합 테스트.
 *
 * <p><b>증명 목표</b>: 같은 회원이 같은 상품을 <b>동시에 여러 번</b> 주문해도, {@code purchase_slot}
 * 의 {@code (member_id, goods_id)} 유니크 제약이 <b>정확히 1건만</b> 성공시키고 나머지는 한도 초과로
 * 거부함을 실제 MariaDB(InnoDB) 위에서 검증한다. 재고는 1건 분량만 차감되고 나머지는 롤백된다.
 *
 * <p>재고 동시성({@link StockConcurrencyTest})과 동일하게 {@code RUN_DB_IT=true} 일 때만 실행된다.
 */
@SpringBootTest(classes = CoreTestApplication.class)
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class PurchaseLimitConcurrencyTest {

    private static final int INITIAL_STOCK = 100;

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
    private PaymentRepository paymentRepository;
    @Autowired
    private PurchaseSlotRepository purchaseSlotRepository;

    private Long memberId;
    private Long goodsId;

    @BeforeEach
    void setUp() {
        cleanUp();
        memberId = memberRepository.save(Member.builder()
            .email("sensei@schale.gg").password("hashed").name("선생").role(Role.USER).build()).getId();
        // 1인 한도 1건. 재고는 충분(100)히 둬서 '슬롯'이 유일한 제약임을 분명히 한다.
        goodsId = goodsRepository.save(Goods.builder()
            .name("한정 굿즈").price(19_000L).openAt(LocalDateTime.now())
            .maxPurchasePerMember(1).build()).getId();
        stockRepository.save(Stock.builder()
            .goodsId(goodsId).totalQuantity(INITIAL_STOCK).remainQuantity(INITIAL_STOCK).build());
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        // FK 의존성 역순: purchase_slot/payment/order_item → orders → stock → goods → member.
        purchaseSlotRepository.deleteAll();
        paymentRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        stockRepository.deleteAll();
        goodsRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("같은 회원이 같은 상품을 50번 동시 주문해도 정확히 1건만 성공하고 나머지는 한도 초과로 거부된다")
    void concurrent_orders_by_same_member_admit_exactly_one() throws InterruptedException {
        // given — 50개 가상 스레드가 동일 (회원, 상품) 으로 동시에 주문(각 수량 1)
        int threadCount = 50;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger limitRejected = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        // when
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    orderService.createOrder(memberId, goodsId, 1);
                    success.incrementAndGet();
                } catch (PurchaseLimitExceededException e) {
                    limitRejected.incrementAndGet();
                } catch (Exception e) {
                    other.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(20, TimeUnit.SECONDS))
            .as("50건 주문이 20초 내 끝나야 한다").isTrue();
        executor.shutdown();

        // then — 정확히 1건 성공, 나머지 49건 한도 초과, 예기치 못한 예외 0
        assertThat(success.get()).as("정확히 1건만 성공해야 한다").isEqualTo(1);
        assertThat(limitRejected.get()).as("나머지는 모두 한도 초과로 거부").isEqualTo(threadCount - 1);
        assertThat(other.get()).as("예기치 못한 예외는 없어야 한다").isZero();

        // then — 슬롯 1건, 주문 1건, 재고는 정확히 1개만 차감(실패분은 롤백)
        assertThat(purchaseSlotRepository.count()).as("활성 슬롯은 1건").isEqualTo(1);
        assertThat(orderRepository.findByMemberId(memberId)).as("성공한 주문 1건만 영속").hasSize(1);
        assertThat(stockRepository.findByGoodsId(goodsId).orElseThrow().getRemainQuantity())
            .as("재고는 성공한 1건 분량만 차감").isEqualTo(INITIAL_STOCK - 1);
    }
}
