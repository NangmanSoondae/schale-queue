package com.schale.queue.core.domain.outbox.repository;

import com.schale.queue.core.domain.outbox.EventOutbox;
import com.schale.queue.core.domain.outbox.OutboxStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아웃박스 Repository(ADR-007).
 */
public interface EventOutboxRepository extends JpaRepository<EventOutbox, Long> {

    /**
     * 미발행 행을 오래된 순(id 오름차순)으로 한 배치만큼 조회한다. 워커 릴레이가 폴링에 사용한다.
     * 한 틱에 처리할 양을 상한(Top)으로 제한해 장시간 트랜잭션을 막는다.
     */
    List<EventOutbox> findTop100ByStatusOrderByIdAsc(OutboxStatus status);

    /**
     * 발행 확정(broker ack 수신) 행들을 벌크 UPDATE 하나로 {@code SENT} 마킹한다(리뷰 M2).
     * 릴레이가 발행(블로킹 send)을 트랜잭션 밖에서 끝낸 뒤, DB 커넥션은 이 짧은 쓰기에만 점유된다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
        update EventOutbox e
           set e.status = com.schale.queue.core.domain.outbox.OutboxStatus.SENT,
               e.sentAt = :sentAt
         where e.id in :ids
        """)
    void markSent(@Param("ids") List<Long> ids, @Param("sentAt") LocalDateTime sentAt);
}
