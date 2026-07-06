package com.schale.queue.core.domain.order.repository;

import com.schale.queue.core.domain.order.OrderItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 주문 항목 Repository.
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** 특정 주문의 항목 목록 조회. */
    List<OrderItem> findByOrderId(Long orderId);

    /**
     * 회원의 상품별 <b>누적 유효 주문 수량</b>(취소 제외 = 예약 PENDING + 확정 COMPLETED)을 합산한다.
     * 1인 구매 한도(P-O3)의 수량 검사가 "이번 주문"이 아니라 <b>누적</b> 기준이 되도록 한다(리뷰 M7) —
     * 한도 2개 상품을 1개 산 회원은 1개를 더 살 수 있고, 2개째부터 거부된다.
     */
    @Query("""
        select coalesce(sum(oi.quantity), 0)
          from OrderItem oi, Order o
         where o.id = oi.orderId
           and o.memberId = :memberId
           and oi.goodsId = :goodsId
           and o.orderStatus <> com.schale.queue.core.domain.order.OrderStatus.CANCELLED
        """)
    long sumActiveQuantityByMemberIdAndGoodsId(@Param("memberId") Long memberId, @Param("goodsId") Long goodsId);
}
