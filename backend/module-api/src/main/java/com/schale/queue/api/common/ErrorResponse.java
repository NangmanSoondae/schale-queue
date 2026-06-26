package com.schale.queue.api.common;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 공통 에러 응답 DTO. API 전역에서 일관된 실패 표현을 제공한다.
 *
 * @param code      기계가 분기할 수 있는 에러 코드(예: ADMISSION_REQUIRED)
 * @param message   사람이 읽는 설명
 * @param timestamp 발생 시각
 */
@Schema(description = "에러 응답")
public record ErrorResponse(
    @Schema(description = "에러 코드", example = "ADMISSION_REQUIRED")
    String code,

    @Schema(description = "에러 메시지", example = "유효한 입장 토큰이 없습니다.")
    String message,

    @Schema(description = "발생 시각")
    LocalDateTime timestamp
) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, LocalDateTime.now());
    }
}
