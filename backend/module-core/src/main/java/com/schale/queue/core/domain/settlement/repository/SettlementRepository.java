package com.schale.queue.core.domain.settlement.repository;

import com.schale.queue.core.domain.settlement.Settlement;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 정산 원장 Repository(ADR-002 후방 컨슈머 확장).
 */
public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    /** 주문당 1정산 보장 — 적재 전 이미 정산된 주문인지 확인(컨슈머 멱등 2차 방어). */
    boolean existsByOrderId(Long orderId);

    /** 취소 반제 시 기존 정산 행을 조회한다. 미정산(만료 취소 등) 주문이면 비어 있다. */
    Optional<Settlement> findByOrderId(Long orderId);
}
