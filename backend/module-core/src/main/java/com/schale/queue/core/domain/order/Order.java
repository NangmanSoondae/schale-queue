package com.schale.queue.core.domain.order;

import com.schale.queue.core.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 엔티티. ('order' 는 SQL 예약어이므로 테이블명은 orders)
 *
 * <p>주문의 확정된 사실을 담는다. 변동성이 큰 결제는 {@code Payment} 로 1:1 분리한다. (ADR-001 참조)
 */
@Getter
@Entity
@Table(name = "orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 주문 회원 FK (ID 참조). */
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false, length = 20)
    private OrderStatus orderStatus;

    /** 주문 총액 (KRW). */
    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @Builder
    private Order(Long memberId, OrderStatus orderStatus, Long totalAmount) {
        this.memberId = memberId;
        this.orderStatus = orderStatus;
        this.totalAmount = totalAmount;
    }

    public void changeStatus(OrderStatus orderStatus) {
        this.orderStatus = orderStatus;
    }
}
