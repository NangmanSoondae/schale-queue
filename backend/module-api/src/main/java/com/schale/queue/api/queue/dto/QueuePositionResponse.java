package com.schale.queue.api.queue.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 대기 순번 응답 / SSE {@code position} 이벤트 페이로드.
 *
 * @param position 내 현재 대기 순번(1-based). "내 앞에 (position-1)명".
 * @param waiting  현재 해당 상품의 총 대기 인원.
 */
@Schema(description = "대기 순번")
public record QueuePositionResponse(
    @Schema(description = "내 대기 순번(1-based)", example = "42")
    long position,

    @Schema(description = "총 대기 인원", example = "1234")
    long waiting
) {
}
