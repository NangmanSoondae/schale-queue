package com.schale.queue.api.common;

import com.schale.queue.api.order.AdmissionRequiredException;
import com.schale.queue.core.domain.NotFoundException;
import com.schale.queue.core.domain.goods.SaleNotOpenException;
import com.schale.queue.core.domain.order.PurchaseLimitExceededException;
import com.schale.queue.core.domain.payment.PaymentNotConfirmableException;
import com.schale.queue.core.domain.stock.InsufficientStockException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 전역 예외 핸들러. 도메인/입장/검증 예외를 일관된 {@link ErrorResponse} 와 HTTP 상태로 매핑한다.
 *
 * <p>상태 코드 매핑 원칙:
 * <ul>
 *   <li>입장 토큰 없음(P-O1) → <b>403 Forbidden</b></li>
 *   <li>비즈니스 상태 충돌(품절·한도 초과·판매 전·확정 불가) → <b>409 Conflict</b></li>
 *   <li>자원 없음(상품/재고/결제 — 타인 소유 포함) → <b>404 Not Found</b></li>
 *   <li>잘못된 인자/검증 실패/헤더 누락/본문 파싱 실패 → <b>400 Bad Request</b></li>
 *   <li>그 외 전부(인프라 장애 등 시스템 오류) → <b>500</b> + 일반 메시지(내부 노출 금지)</li>
 * </ul>
 *
 * <p>JDK 범용 예외({@code IllegalStateException})를 409 로 뭉뚱그리지 않는다 — 아웃박스 직렬화 실패
 * 같은 시스템 오류가 "재고 충돌(사용자 탓)"로 오분류되어 재시도를 유도하고 입장 토큰까지 소각시켰다
 * (2026-07-02 리뷰 M3). 비즈니스 거부는 전용 도메인 예외로만 표현한다.
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

    /** 가용 재고 부족(P-S1 정상 품절 거부). */
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientStock(InsufficientStockException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of("STOCK_CONFLICT", e.getMessage()));
    }

    /** 판매 시작(openAt) 이전의 진입/주문(UC-02). */
    @ExceptionHandler(SaleNotOpenException.class)
    public ResponseEntity<ErrorResponse> handleSaleNotOpen(SaleNotOpenException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of("SALE_NOT_OPEN", e.getMessage()));
    }

    /** 1인 구매 한도 초과 또는 활성 주문 중복(P-O3). */
    @ExceptionHandler(PurchaseLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handlePurchaseLimit(PurchaseLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of("PURCHASE_LIMIT_EXCEEDED", e.getMessage()));
    }

    /** 이미 확정/만료된 결제를 다시 확정하려는 경우(P-P2 멱등). */
    @ExceptionHandler(PaymentNotConfirmableException.class)
    public ResponseEntity<ErrorResponse> handlePaymentNotConfirmable(PaymentNotConfirmableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of("PAYMENT_NOT_CONFIRMABLE", e.getMessage()));
    }

    /** 존재하지 않는(또는 요청자 소유가 아닌) 상품/재고/결제/주문(리뷰 M3 — 과거 400 이던 것을 REST 시맨틱대로). */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse.of("NOT_FOUND", e.getMessage()));
    }

    /** 잘못된 인자(음수 수량 등 입력 값 오류). */
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

    /** 본문 JSON 파싱 실패(malformed body). 프레임워크 기본 응답 대신 ErrorResponse 계약을 지킨다(리뷰 M4). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
            .body(ErrorResponse.of("MALFORMED_BODY", "요청 본문을 해석할 수 없습니다."));
    }

    /** 경로 변수/헤더 타입 불일치(예: X-Member-Id: abc). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
            .body(ErrorResponse.of("INVALID_TYPE", e.getName() + " 값의 형식이 올바르지 않습니다."));
    }

    /**
     * 최후 방어선: 위에서 매핑되지 않은 모든 예외 = 시스템 오류. 장애 상황에서도 클라이언트가 기대하는
     * {@link ErrorResponse} 계약을 유지하고(리뷰 M4 — 프레임워크 기본 /error 본문으로 새지 않게),
     * 내부 메시지는 응답에 싣지 않는다(§5.3.2). 원인은 서버 로그로만 남긴다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외 — 500 으로 응답", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.of("INTERNAL_ERROR", "일시적인 서버 오류입니다. 잠시 후 다시 시도해 주세요."));
    }
}
