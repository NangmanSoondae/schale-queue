package com.schale.queue.core.domain.payment;

/**
 * 결제를 확정할 수 없는 상태일 때 던지는 예외(P-P2 멱등).
 *
 * <p>이미 PAID/EXPIRED/CANCELLED 등 종결 상태인 결제를 다시 확정하려 할 때 발생한다.
 * 전역 핸들러가 {@code 409 Conflict} 로 매핑한다.
 */
public class PaymentNotConfirmableException extends RuntimeException {

    public PaymentNotConfirmableException(String message) {
        super(message);
    }
}
