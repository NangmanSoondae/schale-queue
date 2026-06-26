package com.schale.queue.core.domain.payment.repository;

import com.schale.queue.core.domain.payment.Payment;
import com.schale.queue.core.domain.payment.PaymentStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * 결제 Repository.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** 주문 기준 결제 조회 (Order ↔ Payment 1:1). */
    Optional<Payment> findByOrderId(Long orderId);

    /**
     * 주문 기준 비관적 쓰기 락 조회. 결제 확정(UC-06)과 만료 해제(UC-07)가 같은 결제를 동시에
     * 건드리는 경합을 직렬화하는 진입점이다. 락 후 상태({@code status==READY})를 재확인해 멱등을 보장한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select p from Payment p where p.orderId = :orderId")
    Optional<Payment> findByOrderIdWithPessimisticLock(@Param("orderId") Long orderId);

    /** PG 승인번호 기준 조회 (웹훅/승인 콜백 처리용). */
    Optional<Payment> findByPaymentUid(String paymentUid);

    /**
     * 만료 대상 결제 조회: 특정 상태이면서 만료 시각이 지난 건.
     * module-worker 가 타임아웃 결제를 EXPIRED 로 정리할 때 사용한다. (ADR-001)
     */
    List<Payment> findByStatusAndTimeoutAtBefore(PaymentStatus status, LocalDateTime time);
}
