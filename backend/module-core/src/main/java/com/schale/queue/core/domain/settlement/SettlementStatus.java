package com.schale.queue.core.domain.settlement;

/**
 * 정산 원장 행의 상태(ADR-002 후방 컨슈머 확장).
 *
 * <ul>
 *   <li>{@link #PENDING_PAYOUT} — 주문 완료(결제 확정)로 정산이 적재됨. 판매자 지급 대기.</li>
 *   <li>{@link #REVERSED} — 완료됐던 주문이 취소(환불)되어 정산이 반제됨.</li>
 * </ul>
 */
public enum SettlementStatus {
    PENDING_PAYOUT,
    REVERSED
}
