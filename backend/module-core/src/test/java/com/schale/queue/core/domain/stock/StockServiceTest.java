package com.schale.queue.core.domain.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.schale.queue.core.domain.NotFoundException;
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
 * <p>DB 없이 Repository 를 mock 으로 대체하여, 모든 전이(예약/확정/해제)가 <b>비관적 락 메서드를
 * 경유</b>하고 도메인 규칙을 올바르게 호출하는지 격리 검증한다. (동시성 자체는 통합 테스트에서 증명)
 */
@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    @Mock
    private StockRepository stockRepository;

    @InjectMocks
    private StockService stockService;

    private static Stock stockOf(int total, int available, int reserved, int sold) {
        return Stock.builder().goodsId(1L)
            .totalQuantity(total).availableQuantity(available)
            .reservedQuantity(reserved).soldQuantity(sold).build();
    }

    @Test
    @DisplayName("예약은 비관적 락 조회를 경유하여 available-- reserved++ 로 이동한다")
    void reserve_uses_pessimistic_lock_and_moves_counters() {
        // given — 가용 100
        Stock stock = stockOf(100, 100, 0, 0);
        given(stockRepository.findByGoodsIdWithPessimisticLock(1L)).willReturn(Optional.of(stock));

        // when — 10개 예약
        stockService.reserve(1L, 10);

        // then — available 90, reserved 10, 합계 불변. 반드시 비관적 락 메서드 경유.
        assertThat(stock.getAvailableQuantity()).isEqualTo(90);
        assertThat(stock.getReservedQuantity()).isEqualTo(10);
        assertThat(stock.getSoldQuantity()).isZero();
        then(stockRepository).should().findByGoodsIdWithPessimisticLock(1L);
    }

    @Test
    @DisplayName("확정은 비관적 락 조회를 경유하여 reserved-- sold++ 로 이동한다")
    void confirm_moves_reserved_to_sold() {
        Stock stock = stockOf(100, 90, 10, 0);
        given(stockRepository.findByGoodsIdWithPessimisticLock(1L)).willReturn(Optional.of(stock));

        stockService.confirm(1L, 4);

        assertThat(stock.getReservedQuantity()).isEqualTo(6);
        assertThat(stock.getSoldQuantity()).isEqualTo(4);
        assertThat(stock.getAvailableQuantity()).isEqualTo(90);
    }

    @Test
    @DisplayName("해제는 비관적 락 조회를 경유하여 reserved-- available++ 로 복원한다")
    void release_moves_reserved_to_available() {
        Stock stock = stockOf(100, 90, 10, 0);
        given(stockRepository.findByGoodsIdWithPessimisticLock(1L)).willReturn(Optional.of(stock));

        stockService.release(1L, 3);

        assertThat(stock.getReservedQuantity()).isEqualTo(7);
        assertThat(stock.getAvailableQuantity()).isEqualTo(93);
        assertThat(stock.getSoldQuantity()).isZero();
    }

    @Test
    @DisplayName("재고가 존재하지 않으면 NotFoundException 을 던진다(404 매핑, 리뷰 M3)")
    void reserve_throws_when_stock_not_found() {
        given(stockRepository.findByGoodsIdWithPessimisticLock(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> stockService.reserve(999L, 1))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("재고가 존재하지 않습니다");
    }
}
