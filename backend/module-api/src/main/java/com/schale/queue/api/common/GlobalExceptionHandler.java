package com.schale.queue.api.common;

import com.schale.queue.api.order.AdmissionRequiredException;
import com.schale.queue.core.domain.order.PurchaseLimitExceededException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 전역 예외 핸들러. 도메인/입장/검증 예외를 일관된 {@link ErrorResponse} 와 HTTP 상태로 매핑한다.
 *
 * <p>상태 코드 매핑 원칙:
 * <ul>
 *   <li>입장 토큰 없음(P-O1) → <b>403 Forbidden</b></li>
 *   <li>재고 부족 등 상태 충돌({@link IllegalStateException}) → <b>409 Conflict</b></li>
 *   <li>잘못된 인자/검증 실패/헤더 누락 → <b>400 Bad Request</b></li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 유효한 입장 토큰 없이 주문 시도(P-O1). */
    @ExceptionHandler(AdmissionRequiredException.class)
    public ResponseEntity<ErrorResponse> handleAdmissionRequired(AdmissionRequiredException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse.of("ADMISSION_REQUIRED", e.getMessage()));
    }

    /** 재고 부족 등 도메인 상태 충돌(초과 판매 차단). */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of("STOCK_CONFLICT", e.getMessage()));
    }

    /** 1인 구매 한도 초과 또는 활성 주문 중복(P-O3). */
    @ExceptionHandler(PurchaseLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handlePurchaseLimit(PurchaseLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of("PURCHASE_LIMIT_EXCEEDED", e.getMessage()));
    }

    /** 존재하지 않는 상품/재고 등 잘못된 인자. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
            .body(ErrorResponse.of("INVALID_REQUEST", e.getMessage()));
    }

    /** 본문 Bean Validation 실패(@Valid). 필드별 메시지를 모아 돌려준다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
            .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
            .body(ErrorResponse.of("VALIDATION_FAILED", message));
    }

    /** 필수 요청 헤더 누락(예: X-Member-Id). */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
            .body(ErrorResponse.of("MISSING_HEADER", e.getHeaderName() + " 헤더가 필요합니다."));
    }
}
