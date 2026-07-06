package com.schale.queue.admin.tools;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.schale.queue.core.domain.goods.repository.GoodsRepository;
import com.schale.queue.core.domain.stock.repository.StockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link AdminWriteTools} 안전장치 계약 테스트(ADR-009 §3).
 * "확인 없는 파괴적 변경은 DB 에 닿기 전에 거부된다"를 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminWriteToolsTest {

    @Mock private GoodsRepository goodsRepository;
    @Mock private StockRepository stockRepository;
    @Mock private JdbcTemplate jdbc;

    @InjectMocks private AdminWriteTools tools;

    @Test
    @DisplayName("재고 감소는 confirm=true 없이는 DB 접근 전에 거부된다")
    void adjust_stock_decrease_requires_confirm() {
        assertThatThrownBy(() -> tools.adjustStock(1L, -5, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("confirm=true");
        assertThatThrownBy(() -> tools.adjustStock(1L, -5, false))
            .isInstanceOf(IllegalStateException.class);
        then(jdbc).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("재고 입고(양수)는 confirm 없이 허용된다 — 가드 UPDATE 경로로 진행")
    void adjust_stock_increase_needs_no_confirm() {
        org.mockito.BDDMockito.given(jdbc.update(anyString(), any(), any(), any(), any())).willReturn(1);
        org.mockito.BDDMockito.given(jdbc.queryForMap(anyString(), any()))
            .willReturn(java.util.Map.of("total_quantity", 110, "available_quantity", 60));

        tools.adjustStock(1L, 10, null);

        then(jdbc).should().update(anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("가용 재고보다 큰 감소는 가드 UPDATE(0행)로 거부된다 — 예약/판매분 보호")
    void adjust_stock_rejects_below_zero_available() {
        org.mockito.BDDMockito.given(jdbc.update(anyString(), any(), any(), any(), any())).willReturn(0);
        org.mockito.BDDMockito.given(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Boolean.class), any()))
            .willReturn(true);

        assertThatThrownBy(() -> tools.adjustStock(1L, -999, true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("가용 재고");
    }

    @Test
    @DisplayName("오픈 시각 변경은 confirm=true 없이는 거부된다")
    void update_open_at_requires_confirm() {
        assertThatThrownBy(() -> tools.updateGoodsOpenAt(1L, "2026-07-10T01:00:00", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("confirm=true");
        then(jdbc).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("잘못된 시각 형식·음수 가격은 저장 전에 거부된다")
    void validates_inputs_before_persistence() {
        assertThatThrownBy(() -> tools.updateGoodsOpenAt(1L, "2026-07-10 01:00", true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ISO-8601");
        assertThatThrownBy(() -> tools.createGoods("x", -1, "2026-07-10T01:00:00", 1, 10, 0))
            .isInstanceOf(IllegalArgumentException.class);
        then(goodsRepository).should(never()).save(any());
        then(stockRepository).should(never()).save(any());
    }
}
