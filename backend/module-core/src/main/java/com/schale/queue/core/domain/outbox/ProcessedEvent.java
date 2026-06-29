package com.schale.queue.core.domain.outbox;

import com.schale.queue.core.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 컨슈머 멱등 처리 기록(ADR-007).
 *
 * <p>아웃박스는 at-least-once 라 같은 이벤트가 중복 전달될 수 있다. 컨슈머는 처리 전에
 * {@code (eventId, consumerGroup)} 존재 여부를 확인해 이미 처리한 이벤트를 건너뛴다(재처리 차단).
 * 같은 이벤트를 여러 컨슈머 그룹이 각자 한 번씩 처리하므로 그룹을 키에 포함한다.
 *
 * <p>{@code createdAt}(BaseTimeEntity)이 곧 처리 시각이다.
 */
@Getter
@Entity
@Table(name = "processed_event",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_processed_event_id_group", columnNames = {"event_id", "consumer_group"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "consumer_group", nullable = false, length = 50)
    private String consumerGroup;

    @Builder
    private ProcessedEvent(String eventId, String consumerGroup) {
        this.eventId = eventId;
        this.consumerGroup = consumerGroup;
    }

    public static ProcessedEvent of(String eventId, String consumerGroup) {
        return ProcessedEvent.builder().eventId(eventId).consumerGroup(consumerGroup).build();
    }
}
