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
 * 재고 엔티티.
 *
 * <p>쓰기 경합이 집중되는 데이터로, 상품({@code Goods})과 1:1 로 분리되어 있다. (ADR-001 참조)
 * 실제 동시성 제어(비관적/분산 락)는 Phase 2~3 에서 Repository/Service 계층에 적용한다.
 * 본 엔티티의 {@link #decrease(int)} 는 도메인 차원의 마지막 무결성 가드 역할을 한다.
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

    @Column(name = "remain_quantity", nullable = false)
    private int remainQuantity;

    @Builder
    private Stock(Long goodsId, int totalQuantity, int remainQuantity) {
        this.goodsId = goodsId;
        this.totalQuantity = totalQuantity;
        this.remainQuantity = remainQuantity;
    }

    /**
     * 재고를 차감한다. 잔여 수량이 부족하면 차감하지 않고 예외를 던진다.
     *
     * @param quantity 차감 수량 (양수)
     * @throws IllegalArgumentException 수량이 0 이하인 경우
     * @throws IllegalStateException    잔여 재고가 부족한 경우(초과판매 방지)
     */
    public void decrease(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("차감 수량은 1 이상이어야 합니다. quantity=" + quantity);
        }
        if (this.remainQuantity < quantity) {
            throw new IllegalStateException(
                "잔여 재고가 부족합니다. remain=" + this.remainQuantity + ", request=" + quantity);
        }
        this.remainQuantity -= quantity;
    }
}
