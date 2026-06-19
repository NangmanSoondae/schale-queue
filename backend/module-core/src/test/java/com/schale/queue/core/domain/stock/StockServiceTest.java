package com.schale.queue.core.domain.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.schale.queue.core.domain.stock.repository.StockRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link StockService} 단위 테스트 (BDDMockito + AssertJ).
 *
 * <p>DB 없이 Repository 를 mock 으로 대체하여, 차감 로직이 <b>비관적 락 메서드를 경유</b>하고
 * 도메인 규칙을 올바르게 호출하는지 격리 검증한다. (동시성 자체는 통합 테스트에서 증명)
 */
@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    @Mock
    private StockRepository stockRepository;

    @InjectMocks
    private StockService stockService;

    @Test
    @DisplayName("재고 차감 시 비관적 락 조회를 경유하여 잔여 수량이 줄어든다")
    void decrease_uses_pessimistic_lock_and_reduces_remain() {
        // given — 잔여 100개 재고를 락 조회가 반환하도록 스텁
        Stock stock = Stock.builder()
            .goodsId(1L)
            .totalQuantity(100)
            .remainQuantity(100)
            .build();
        given(stockRepository.findByGoodsIdWithPessimisticLock(1L)).willReturn(Optional.of(stock));

        // when — 10개 차감
        stockService.decrease(1L, 10);

        // then — 잔여 90, 그리고 반드시 비관적 락 메서드로 조회되었음을 검증
        assertThat(stock.getRemainQuantity()).isEqualTo(90);
        then(stockRepository).should().findByGoodsIdWithPessimisticLock(1L);
    }

    @Test
    @DisplayName("재고가 존재하지 않으면 IllegalArgumentException 을 던진다")
    void decrease_throws_when_stock_not_found() {
        // given — 락 조회가 빈 결과를 반환
        given(stockRepository.findByGoodsIdWithPessimisticLock(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> stockService.decrease(999L, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("재고가 존재하지 않습니다");
    }
}
