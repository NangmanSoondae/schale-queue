package com.schale.queue.core.domain.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Stock} 예약 기반 3-카운터 도메인 단위 테스트(ADR-004, P-S2).
 *
 * <p>전이(예약/확정/해제)가 두 카운터를 함께 옮겨 <b>합계 불변식 total=available+reserved+sold</b>
 * 를 보존하는지, 가드(가용 부족·예약 부족)가 작동하는지 검증한다.
 */
class StockTest {

    private static Stock fresh(int total) {
        return Stock.builder().goodsId(1L)
            .totalQuantity(total).availableQuantity(total).reservedQuantity(0).soldQuantity(0).build();
    }

    private static int sum(Stock s) {
        return s.getAvailableQuantity() + s.getReservedQuantity() + s.getSoldQuantity();
    }

    @Test
    @DisplayName("예약→확정 생애주기 동안 합계 불변식이 항상 성립한다")
    void reserve_then_confirm_keeps_invariant() {
        Stock stock = fresh(10);

        stock.reserve(3);   // available 7, reserved 3
        assertThat(stock.getAvailableQuantity()).isEqualTo(7);
        assertThat(stock.getReservedQuantity()).isEqualTo(3);
        assertThat(sum(stock)).isEqualTo(stock.getTotalQuantity());

        stock.confirm(2);   // reserved 1, sold 2
        assertThat(stock.getReservedQuantity()).isEqualTo(1);
        assertThat(stock.getSoldQuantity()).isEqualTo(2);
        assertThat(sum(stock)).isEqualTo(stock.getTotalQuantity());
    }

    @Test
    @DisplayName("예약을 해제하면 available 로 복원되고 불변식이 유지된다")
    void release_restores_available() {
        Stock stock = fresh(10);
        stock.reserve(4);   // available 6, reserved 4

        stock.release(4);   // available 10, reserved 0

        assertThat(stock.getAvailableQuantity()).isEqualTo(10);
        assertThat(stock.getReservedQuantity()).isZero();
        assertThat(sum(stock)).isEqualTo(10);
    }

    @Test
    @DisplayName("가용 재고보다 많이 예약하려 하면 거부하고 카운터를 건드리지 않는다(P-S1)")
    void reserve_rejects_when_insufficient_available() {
        Stock stock = fresh(5);

        assertThatThrownBy(() -> stock.reserve(6))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("잔여 재고가 부족");
        assertThat(stock.getAvailableQuantity()).isEqualTo(5);
        assertThat(stock.getReservedQuantity()).isZero();
    }

    @Test
    @DisplayName("예약량보다 많이 확정/해제하려 하면 거부한다")
    void confirm_or_release_rejects_when_insufficient_reserved() {
        Stock stock = fresh(10);
        stock.reserve(2);

        assertThatThrownBy(() -> stock.confirm(3)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> stock.release(3)).isInstanceOf(IllegalStateException.class);
        // 거부 후에도 상태 불변
        assertThat(stock.getReservedQuantity()).isEqualTo(2);
        assertThat(sum(stock)).isEqualTo(10);
    }

    @Test
    @DisplayName("수량이 0 이하면 IllegalArgumentException")
    void rejects_non_positive_quantity() {
        Stock stock = fresh(10);
        assertThatThrownBy(() -> stock.reserve(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stock.reserve(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
