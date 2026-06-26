package com.schale.queue.core.domain.stock;

import com.schale.queue.core.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 재고 엔티티 — 예약 기반 3-카운터 모델(ADR-004, P-S2).
 *
 * <p>쓰기 경합이 집중되는 데이터로 상품({@code Goods})과 1:1 분리된다(ADR-001). 차감을 "영구 소비"가
 * 아니라 <b>시한부 예약(soft hold)</b>으로 다뤄, 오버셀 차단과 재고 묶임 방지를 동시에 만족한다.
 *
 * <ul>
 *   <li>{@code totalQuantity} — <b>불변 앵커</b>(원본 총량, 재입고 시에만 변경)</li>
 *   <li>{@code availableQuantity} — 아직 아무도 잡지 않은 가용 수량</li>
 *   <li>{@code reservedQuantity} — 예약했으나 결제 전(보류) 수량</li>
 *   <li>{@code soldQuantity} — 결제 확정된 판매 수량</li>
 * </ul>
 *
 * <p><b>합계 불변식</b>: {@code total == available + reserved + sold} 항상 성립, {@code available >= 0}.
 * 각 전이는 <b>두 카운터를 함께</b> 옮겨 불변식을 보존한다(누수 시 합계 대조로 즉시 탐지).
 * 동시성 보호(비관적 락)는 {@code StockService}/{@code StockRepository} 계층이 담당한다.
 */
@Getter
@Entity
@Table(name = "stock")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Stock extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 상품 FK (ID 참조, 1:1). */
    @Column(name = "goods_id", nullable = false, unique = true)
    private Long goodsId;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Column(name = "sold_quantity", nullable = false)
    private int soldQuantity;

    @Builder
    private Stock(Long goodsId, int totalQuantity, int availableQuantity, int reservedQuantity, int soldQuantity) {
        this.goodsId = goodsId;
        this.totalQuantity = totalQuantity;
        this.availableQuantity = availableQuantity;
        this.reservedQuantity = reservedQuantity;
        this.soldQuantity = soldQuantity;
    }

    /**
     * 주문 생성 시 재고를 예약한다(P-S2). {@code available-- , reserved++}.
     *
     * @throws IllegalArgumentException 수량이 0 이하인 경우
     * @throws IllegalStateException    가용 재고가 부족한 경우(초과 판매 방지, P-S1)
     */
    public void reserve(int quantity) {
        requirePositive(quantity);
        if (this.availableQuantity < quantity) {
            throw new IllegalStateException(
                "잔여 재고가 부족합니다. available=" + this.availableQuantity + ", request=" + quantity);
        }
        this.availableQuantity -= quantity;
        this.reservedQuantity += quantity;
    }

    /**
     * 결제 확정 시 예약을 판매로 확정한다(P-S2). {@code reserved-- , sold++}.
     * (호출 측 = 결제 성공 흐름, UC-06 — Phase 4 에서 연결)
     *
     * @throws IllegalStateException 확정할 예약 수량이 부족한 경우
     */
    public void confirm(int quantity) {
        requirePositive(quantity);
        if (this.reservedQuantity < quantity) {
            throw new IllegalStateException(
                "확정할 예약 수량이 부족합니다. reserved=" + this.reservedQuantity + ", request=" + quantity);
        }
        this.reservedQuantity -= quantity;
        this.soldQuantity += quantity;
    }

    /**
     * 결제 만료/실패/취소 시 예약을 해제(복원)한다(P-S2/P-S4). {@code reserved-- , available++}.
     * (호출 측 = 만료 워커/취소 흐름, UC-07 — Phase 4 에서 연결)
     *
     * @throws IllegalStateException 해제할 예약 수량이 부족한 경우
     */
    public void release(int quantity) {
        requirePositive(quantity);
        if (this.reservedQuantity < quantity) {
            throw new IllegalStateException(
                "해제할 예약 수량이 부족합니다. reserved=" + this.reservedQuantity + ", request=" + quantity);
        }
        this.reservedQuantity -= quantity;
        this.availableQuantity += quantity;
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("수량은 1 이상이어야 합니다. quantity=" + quantity);
        }
    }
}
