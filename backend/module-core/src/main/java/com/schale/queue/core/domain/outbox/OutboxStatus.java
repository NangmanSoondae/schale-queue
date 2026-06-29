package com.schale.queue.core.domain.outbox;

/**
 * 아웃박스 행의 발행 상태(ADR-007).
 *
 * <ul>
 *   <li>{@link #PENDING} — 적재됐으나 아직 Kafka 로 발행되지 않음(릴레이 폴링 대상).</li>
 *   <li>{@link #SENT} — broker ack 까지 받아 발행 완료됨.</li>
 * </ul>
 */
public enum OutboxStatus {
    PENDING,
    SENT
}
