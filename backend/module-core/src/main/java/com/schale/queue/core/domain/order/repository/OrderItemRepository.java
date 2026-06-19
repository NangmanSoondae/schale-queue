package com.schale.queue.core.domain.order.repository;

import com.schale.queue.core.domain.order.OrderItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 주문 항목 Repository.
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** 특정 주문의 항목 목록 조회. */
    List<OrderItem> findByOrderId(Long orderId);
}
