package com.schale.queue.api.queue.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * SSE {@code admitted} 이벤트 페이로드. 입장 토큰이 발급되어 주문이 가능해졌음을 알린다(P-O1).
 * 이 이벤트 전송 직후 스트림은 종료된다.
 *
 * @param goodsId 입장 가능해진 상품 ID
 */
@Schema(description = "입장 알림")
public record AdmissionNotice(
    @Schema(description = "입장 가능해진 상품 ID", example = "1")
    Long goodsId
) {
}
