package com.schale.queue.api.health;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 헬스 체크 응답 DTO.
 *
 * @param message    서버 가동 메시지
 * @param serverTime 응답 시점의 서버 시각
 */
@Schema(description = "헬스 체크 응답")
public record HealthResponse(
    @Schema(description = "서버 가동 메시지", example = "Schale Queue Server is Running!")
    String message,

    @Schema(description = "서버 현재 시각", example = "2026-06-19T16:00:00")
    LocalDateTime serverTime
) {
}
