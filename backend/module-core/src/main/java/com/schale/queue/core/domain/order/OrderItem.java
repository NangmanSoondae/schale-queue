package com.schale.queue.core.domain.order;

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
 * 주문 항목 엔티티. (주문 1 : N 항목)
 *
 * <p>{@code orderPrice} 는 주문 시점의 단가 스냅샷이다. 이후 상품 가격이 변경되어도
 * 주문 내역의 금액은 보존된다.
 */
@Getter
@Entity
@Table(name = "order_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 주문 FK (ID 참조). */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** 상품 FK (ID 참조). */
    @Column(name = "goods_id", nullable = false)
    private Long goodsId;

    @Column(nullable = false)
    private int quantity;

    /** 주문 시점 단가 스냅샷 (KRW). */
    @Column(name = "order_price", nullable = false)
    private Long orderPrice;

    @Builder
    private OrderItem(Long orderId, Long goodsId, int quantity, Long orderPrice) {
        this.orderId = orderId;
        this.goodsId = goodsId;
        this.quantity = quantity;
        this.orderPrice = orderPrice;
    }
}
