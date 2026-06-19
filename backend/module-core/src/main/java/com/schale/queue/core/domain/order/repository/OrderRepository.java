package com.schale.queue.core.domain.order.repository;

import com.schale.queue.core.domain.order.Order;
import com.schale.queue.core.domain.order.OrderStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 주문 Repository.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /** 회원별 주문 내역 조회. */
    List<Order> findByMemberId(Long memberId);

    /** 상태별 주문 조회 (배치/통계/만료 처리용). */
    List<Order> findByOrderStatus(OrderStatus orderStatus);
}
