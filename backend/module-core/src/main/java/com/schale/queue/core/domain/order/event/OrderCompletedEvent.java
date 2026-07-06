package com.schale.queue.core.domain.order.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 주문 완료(결제 확정) 도메인 이벤트(ADR-002 후방 파이프라인).
 *
 * <p>결제가 확정되면 {@code PaymentService.confirm} 이 이 이벤트를 발행한다. core 는 Kafka 에 직접
 * 의존하지 않고 <b>스프링 ApplicationEvent</b> 로 발행하며, api 계층의 브리지가 트랜잭션 커밋 후
 * Kafka 토픽({@code order.completed})으로 내보낸다(발행 신뢰성 = AFTER_COMMIT, 무유실 보강은 후속 아웃박스).
 *
 * <p>{@code eventId} 는 컨슈머 멱등/중복 제거의 기준이 된다(at-least-once 대비).
 *
 * @param eventId     이벤트 고유 ID(멱등 키)
 * @param orderId     완료된 주문 ID
 * @param memberId    주문 회원 ID
 * @param totalAmount 주문 총액(KRW)
 * @param occurredAt  발생 시각
 */
public record OrderCompletedEvent(
    String eventId,
    Long orderId,
    Long memberId,
    Long totalAmount,
    LocalDateTime occurredAt
) {

    /** 발행 대상 Kafka 토픽. 발행(아웃박스 릴레이)·구독(컨슈머)이 공유하는 단일 출처. */
    public static final String TOPIC = "order.completed";

    public static OrderCompletedEvent of(Long orderId, Long memberId, Long totalAmount) {
        // 시간 출처 통일(리뷰 '시간대 3원화'): 도메인 Clock(UTC, TimeConfig)과 같은 출처로 기록한다.
        // 정적 팩토리라 빈 주입 대신 동일 출처(systemUTC)를 직접 쓴다.
        return new OrderCompletedEvent(
            UUID.randomUUID().toString(), orderId, memberId, totalAmount,
            LocalDateTime.now(java.time.Clock.systemUTC()));
    }
}
