package com.schale.queue.core.domain.order;

/**
 * 주문 상태.
 */
public enum OrderStatus {
    /** 주문 생성, 결제 대기. */
    PENDING,
    /** 결제 완료. */
    COMPLETED,
    /** 주문 취소(결제 실패/타임아웃/사용자 취소). */
    CANCELLED
}
