package com.schale.queue.core.domain.payment;

/**
 * 결제 상태.
 */
public enum PaymentStatus {
    /** 결제 생성, PG 승인 대기. */
    READY,
    /** 결제 승인 완료. */
    PAID,
    /** 결제 실패. */
    FAILED,
    /** 결제 취소. */
    CANCELLED,
    /** 결제 만료(timeoutAt 경과, 워커가 정리). */
    EXPIRED
}
