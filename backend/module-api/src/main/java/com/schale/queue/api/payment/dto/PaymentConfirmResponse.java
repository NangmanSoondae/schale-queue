package com.schale.queue.api.payment.dto;

import com.schale.queue.core.domain.order.OrderStatus;
import com.schale.queue.core.domain.payment.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 결제 확정 응답 DTO.
 *
 * @param orderId       확정된 주문 ID
 * @param paymentStatus 결제 상태(확정 성공 시 PAID)
 * @param orderStatus   주문 상태(확정 성공 시 COMPLETED)
 */
@Schema(description = "결제 확정 응답")
public record PaymentConfirmResponse(
    @Schema(description = "주문 ID", example = "1001")
    Long orderId,

    @Schema(description = "결제 상태", example = "PAID")
    PaymentStatus paymentStatus,

    @Schema(description = "주문 상태", example = "COMPLETED")
    OrderStatus orderStatus
) {

    public static PaymentConfirmResponse paid(Long orderId) {
        return new PaymentConfirmResponse(orderId, PaymentStatus.PAID, OrderStatus.COMPLETED);
    }
}
