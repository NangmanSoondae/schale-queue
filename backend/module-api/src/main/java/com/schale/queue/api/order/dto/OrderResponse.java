package com.schale.queue.api.order.dto;

import com.schale.queue.core.domain.order.Order;
import com.schale.queue.core.domain.order.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 주문 생성 응답 DTO.
 *
 * @param orderId     생성된 주문 ID
 * @param orderStatus 주문 상태(생성 직후 PENDING)
 * @param totalAmount 주문 총액(KRW)
 */
@Schema(description = "주문 생성 응답")
public record OrderResponse(
    @Schema(description = "생성된 주문 ID", example = "1001")
    Long orderId,

    @Schema(description = "주문 상태", example = "PENDING")
    OrderStatus orderStatus,

    @Schema(description = "주문 총액(KRW)", example = "49000")
    Long totalAmount
) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getOrderStatus(), order.getTotalAmount());
    }
}
