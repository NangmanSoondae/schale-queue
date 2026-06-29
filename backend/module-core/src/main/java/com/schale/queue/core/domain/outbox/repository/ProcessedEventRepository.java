package com.schale.queue.core.domain.outbox.repository;

import com.schale.queue.core.domain.outbox.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 컨슈머 멱등 기록 Repository(ADR-007).
 */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    /** 해당 이벤트를 해당 컨슈머 그룹이 이미 처리했는지 여부(멱등 가드). */
    boolean existsByEventIdAndConsumerGroup(String eventId, String consumerGroup);
}
