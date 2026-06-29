package com.schale.queue.core.domain.order.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 주문 취소 도메인 이벤트(ADR-002 후방 파이프라인 / ADR-007 아웃박스).
 *
 * <p>결제 만료(UC-07) 등으로 주문이 취소(CANCELLED)되면 {@code PaymentService.expireOne} 이 이 이벤트를
 * 발행한다. 발행 경로는 주문완료({@link OrderCompletedEvent})와 동일하다 — core 는 Kafka 비의존으로
 * 같은 트랜잭션에서 아웃박스에 기록하고, 워커 릴레이가 {@code order.cancelled} 토픽으로 내보낸다.
 *
 * <p>{@code reason} 은 취소 사유(예: {@code PAYMENT_EXPIRED})로, 알림 문구·정산 처리 분기에 쓰인다.
 * {@code eventId} 는 컨슈머 멱등/중복 제거의 기준이다(at-least-once 대비).
 *
 * @param eventId    이벤트 고유 ID(멱등 키)
 * @param orderId    취소된 주문 ID
 * @param memberId   주문 회원 ID
 * @param reason     취소 사유(예: PAYMENT_EXPIRED)
 * @param occurredAt 발생 시각
 */
public record OrderCancelledEvent(
    String eventId,
    Long orderId,
    Long memberId,
    String reason,
    LocalDateTime occurredAt
) {

    /** 발행 대상 Kafka 토픽. 발행(아웃박스 릴레이)·구독(컨슈머)이 공유하는 단일 출처. */
    public static final String TOPIC = "order.cancelled";

    /** 결제 만료로 인한 취소 사유 상수. */
    public static final String REASON_PAYMENT_EXPIRED = "PAYMENT_EXPIRED";

    public static OrderCancelledEvent of(Long orderId, Long memberId, String reason) {
        return new OrderCancelledEvent(UUID.randomUUID().toString(), orderId, memberId, reason, LocalDateTime.now());
    }
}
