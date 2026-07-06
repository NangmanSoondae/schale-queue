package com.schale.queue.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schale.queue.core.CoreTestApplication;
import com.schale.queue.core.domain.goods.Goods;
import com.schale.queue.core.domain.goods.repository.GoodsRepository;
import com.schale.queue.core.domain.member.Member;
import com.schale.queue.core.domain.member.Role;
import com.schale.queue.core.domain.member.repository.MemberRepository;
import com.schale.queue.core.domain.order.repository.OrderItemRepository;
import com.schale.queue.core.domain.order.repository.OrderRepository;
import com.schale.queue.core.domain.order.repository.PurchaseSlotRepository;
import com.schale.queue.core.domain.payment.PaymentService;
import com.schale.queue.core.domain.payment.repository.PaymentRepository;
import com.schale.queue.core.domain.stock.Stock;
import com.schale.queue.core.domain.stock.repository.StockRepository;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P-O3 누적 한도의 <b>스냅샷 인터리빙</b> 통합 테스트(리뷰2 H-1 회귀 방지).
 *
 * <p><b>재현하는 구멍</b>: 누적 SUM 선검사는 REPEATABLE READ 스냅샷 읽기라, 트랜잭션의 read view
 * 가 고정된 <b>이후</b>에 커밋된 타 주문을 보지 못한다. 그 주문이 confirm 까지 마치면 슬롯도
 * 반납되어 유니크 제약도 통과 — 한도 2 상품을 3개 보유할 수 있었다.
 *
 * <p><b>결정적 재현</b>: 락 타이밍 경합 없이 스냅샷 시차만으로 재현한다.
 * <ol>
 *   <li>외부 트랜잭션 T-A 를 열고 아무 SELECT 로 read view 를 고정한다.</li>
 *   <li>T-A 밖(별도 스레드 = 별도 트랜잭션)에서 같은 회원이 1개 주문 + confirm 을 커밋한다
 *       (T-A 스냅샷엔 안 보이고, 슬롯은 반납된 상태).</li>
 *   <li>T-A 안에서 {@code createOrder(한도치 수량)} 호출 — 선검사(스냅샷 SUM=0)는 통과하지만,
 *       ⑧ 잠금 재검사(FOR UPDATE = 최신 커밋 읽기)가 초과를 감지해 거부해야 한다.</li>
 * </ol>
 */
@SpringBootTest(classes = CoreTestApplication.class)
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class PurchaseLimitInterleavingIT {

    private static final int LIMIT = 2;

    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private MemberRepository memberRepository;
    @Autowired private GoodsRepository goodsRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PurchaseSlotRepository purchaseSlotRepository;

    private Long memberId;
    private Long goodsId;

    @BeforeEach
    void setUp() {
        cleanUp();
        memberId = memberRepository.save(Member.builder()
            .email("interleave@schale.gg").password("hashed").name("선생").role(Role.USER).build()).getId();
        goodsId = goodsRepository.save(Goods.builder()
            .name("한도2 한정판").price(19_000L)
            .openAt(LocalDateTime.now().minusYears(1))    // UTC/KST 어느 쪽으로 해석돼도 확실한 과거
            .maxPurchasePerMember(LIMIT)
            .build()).getId();
        stockRepository.save(Stock.builder()
            .goodsId(goodsId).totalQuantity(10).availableQuantity(10)
            .reservedQuantity(0).soldQuantity(0).build());
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
    @DisplayName("스냅샷 고정 후 끼어든 '주문+확정' 이 있으면, 잠금 재검사가 누적 한도 초과를 거부한다(H-1)")
    void locking_recheck_rejects_snapshot_stale_over_limit() {
        TransactionTemplate txA = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> txA.executeWithoutResult(status -> {
            // ① T-A 의 read view 고정 — 이 시점의 유효 수량은 0
            long snapshotSum = orderItemRepository.sumActiveQuantityByMemberIdAndGoodsId(memberId, goodsId);
            assertThat(snapshotSum).isZero();

            // ② T-A 밖에서 같은 회원의 '1개 주문 + confirm(슬롯 반납)' 을 커밋시킨다.
            //    별도 스레드 = 별도 커넥션/트랜잭션이라 T-A 와 독립적으로 커밋된다.
            CompletableFuture.runAsync(() -> {
                Order intruder = orderService.createOrder(memberId, goodsId, 1);
                paymentService.confirm(intruder.getId(), memberId, null);
            }).join();

            // ③ 스냅샷 SUM 은 여전히 0(끼어든 커밋이 안 보임) — 구멍의 전제 확인
            assertThat(orderItemRepository.sumActiveQuantityByMemberIdAndGoodsId(memberId, goodsId))
                .as("REPEATABLE READ 스냅샷은 끼어든 커밋을 보지 못한다(전제)")
                .isZero();

            // ④ 한도치(2개) 주문 시도 — 선검사는 스냅샷이라 통과하지만(1+2=3>2 인 실제 상태),
            //    ⑧ 잠금 재검사가 최신 커밋(1) + 자기 주문(2) = 3 > 2 를 감지해 던져야 한다.
            orderService.createOrder(memberId, goodsId, LIMIT);
        })).isInstanceOf(PurchaseLimitExceededException.class);

        // ⑤ 사후 정합: 유효 수량은 끼어든 1개뿐이어야 하고(T-A 전체 롤백), 재고도 원복(sold 1 만)
        assertThat(orderItemRepository.sumActiveQuantityByMemberIdAndGoodsId(memberId, goodsId)).isEqualTo(1);
        Stock stock = stockRepository.findByGoodsId(goodsId).orElseThrow();
        assertThat(stock.getAvailableQuantity() + stock.getReservedQuantity() + stock.getSoldQuantity()).isEqualTo(10);
        assertThat(stock.getSoldQuantity()).isEqualTo(1);
        assertThat(stock.getReservedQuantity()).as("초과 주문의 예약이 롤백으로 원복").isZero();
    }
}
