package com.schale.queue.api.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 주문 생성 요청 DTO.
 *
 * <p>회원 식별({@code memberId})은 본문이 아니라 {@code X-Member-Id} 헤더로 받는다.
 * 입장 권한과 신원은 분리(P-Q3)되므로, 향후 인증 필터가 채울 자리를 본문과 섞지 않기 위함이다.
 *
 * @param goodsId  주문 상품 ID
 * @param quantity 주문 수량(양수)
 */
@Schema(description = "주문 생성 요청")
public record CreateOrderRequest(
    @NotNull(message = "goodsId 는 필수입니다.")
    @Schema(description = "주문 상품 ID", example = "1")
    Long goodsId,

    @Positive(message = "quantity 는 1 이상이어야 합니다.")
    @Schema(description = "주문 수량(양수)", example = "1")
    int quantity
) {
}
