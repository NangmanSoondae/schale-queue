package com.schale.queue.core.domain.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.schale.queue.core.CoreTestApplication;
import com.schale.queue.core.domain.goods.Goods;
import com.schale.queue.core.domain.goods.repository.GoodsRepository;
import com.schale.queue.core.domain.stock.repository.StockRepository;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 재고 차감 동시성 통합 테스트.
 *
 * <p><b>증명 목표</b>: 100개의 가상 스레드가 동시에 재고 1개씩을 차감해도,
 * 비관적 락 덕분에 <b>초과 판매가 0건</b>이고 최종 잔여가 정확히 0이 된다.
 *
 * <p>InnoDB 의 실제 행 락 거동을 검증해야 하므로, {@code docker-compose} 로 띄운
 * 실제 MariaDB 에 접속한다(접속 정보는 test {@code application.yml} 의 환경변수).
 * 인프라가 필요한 통합 테스트이므로 {@code RUN_DB_IT=true} 일 때만 실행되어,
 * 인프라 없는 일반 빌드는 영향을 받지 않는다.
 *
 * <p>본 테스트는 실제 DB·트랜잭션이 검증 핵심이라 협력 객체를 mock 하지 않으므로,
 * BDDMockito 대신 AssertJ 와 Given-When-Then 구조로 검증한다.
 * (Repository 격리 검증은 {@link StockServiceTest} 에서 BDDMockito 로 수행)
 */
@SpringBootTest(classes = CoreTestApplication.class)
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class StockConcurrencyTest {

    @Autowired
    private StockService stockService;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private GoodsRepository goodsRepository;

    private Long goodsId;

    @BeforeEach
    void setUp() {
        stockRepository.deleteAll();
        goodsRepository.deleteAll();

        Goods goods = goodsRepository.save(Goods.builder()
            .name("블루 아카이브 한정판 굿즈")
            .price(19_000L)
            .openAt(LocalDateTime.now())
            .build());
        goodsId = goods.getId();

        stockRepository.save(Stock.builder()
            .goodsId(goodsId)
            .totalQuantity(100)
            .remainQuantity(100)
            .build());
    }

    @AfterEach
    void tearDown() {
        stockRepository.deleteAll();
        goodsRepository.deleteAll();
    }

    @Test
    @DisplayName("100개의 가상 스레드가 동시에 1개씩 차감해도 초과 판매 없이 정확히 0개가 남는다")
    void concurrent_decrease_does_not_oversell() throws InterruptedException {
        // given — 재고 100개, 100개의 동시 차감 요청
        int threadCount = 100;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        // when — 100개 가상 스레드가 동시에 재고 1개씩 차감
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    stockService.decrease(goodsId, 1);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.shutdown();

        // then — 잔여 재고 0, 성공 100, 실패 0 (초과 판매 0건)
        Stock result = stockRepository.findByGoodsId(goodsId).orElseThrow();
        assertThat(result.getRemainQuantity())
            .as("잔여 재고는 정확히 0이어야 한다 (음수면 초과 판매 발생)")
            .isZero();
        assertThat(successCount.get()).as("100개 차감 모두 성공해야 한다").isEqualTo(threadCount);
        assertThat(failureCount.get()).as("실패(초과 판매 차단)는 0건이어야 한다").isZero();
    }
}
