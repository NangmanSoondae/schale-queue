package com.schale.queue.core.domain.outbox;

import com.schale.queue.core.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 트랜잭셔널 아웃박스 엔티티(ADR-007, S8 무유실 발행).
 *
 * <p>발행할 이벤트를 <b>비즈니스 변경과 같은 트랜잭션</b>에서 이 테이블에 기록한다(예: {@code PaymentService.confirm}).
 * 주문이 커밋됐으면 "보낼 이벤트"도 무조건 DB 에 남으므로, 앱이 죽어도 유실되지 않는다. 워커의 릴레이가
 * {@code PENDING} 행을 읽어 Kafka 로 발행하고 broker ack 후 벌크 UPDATE({@code EventOutboxRepository.markSent})
 * 로 {@code SENT} 표시한다(at-least-once, 리뷰 M2 — 발행은 DB 트랜잭션 밖).
 *
 * <p>도메인과 발행 파이프라인을 디커플하기 위해 <b>FK 를 두지 않고</b> {@code payload} 에 직렬화된 JSON 만 담는다.
 */
@Getter
@Entity
@Table(name = "event_outbox")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventOutbox extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 이벤트 고유 ID(UUID). 컨슈머 멱등의 기준이자 중복 적재 방지 유니크 키. */
    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    /** 애그리거트 종류(예: {@code ORDER}). */
    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    /** 애그리거트 ID(예: 주문 ID). */
    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    /** 발행 대상 Kafka 토픽. */
    @Column(nullable = false, length = 100)
    private String topic;

    /** Kafka 메시지 키 — 같은 키는 같은 파티션으로 가 순서가 보장된다(예: 주문 ID). */
    @Column(name = "msg_key", length = 64)
    private String msgKey;

    /** 직렬화된 이벤트 본문(JSON). */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    /** 발행(broker ack) 완료 시각. 미발행 시 null. */
    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Builder
    private EventOutbox(String eventId, String aggregateType, String aggregateId, String topic,
                        String msgKey, String payload, OutboxStatus status) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.msgKey = msgKey;
        this.payload = payload;
        this.status = status;
    }

    /** 미발행({@code PENDING}) 아웃박스 행 생성. */
    public static EventOutbox pending(String eventId, String aggregateType, String aggregateId,
                                      String topic, String msgKey, String payload) {
        return EventOutbox.builder()
            .eventId(eventId)
            .aggregateType(aggregateType)
            .aggregateId(aggregateId)
            .topic(topic)
            .msgKey(msgKey)
            .payload(payload)
            .status(OutboxStatus.PENDING)
            .build();
    }
}
