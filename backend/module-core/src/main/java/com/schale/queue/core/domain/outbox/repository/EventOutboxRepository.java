package com.schale.queue.core.domain.outbox.repository;

import com.schale.queue.core.domain.outbox.EventOutbox;
import com.schale.queue.core.domain.outbox.OutboxStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 아웃박스 Repository(ADR-007).
 */
public interface EventOutboxRepository extends JpaRepository<EventOutbox, Long> {

    /**
     * 미발행 행을 오래된 순(id 오름차순)으로 한 배치만큼 조회한다. 워커 릴레이가 폴링에 사용한다.
     * 한 틱에 처리할 양을 상한(Top)으로 제한해 장시간 트랜잭션을 막는다.
     */
    List<EventOutbox> findTop100ByStatusOrderByIdAsc(OutboxStatus status);
}
