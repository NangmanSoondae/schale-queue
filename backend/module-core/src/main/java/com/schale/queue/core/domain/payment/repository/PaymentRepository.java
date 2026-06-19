package com.schale.queue.core.domain.payment.repository;

import com.schale.queue.core.domain.payment.Payment;
import com.schale.queue.core.domain.payment.PaymentStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 결제 Repository.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** 주문 기준 결제 조회 (Order ↔ Payment 1:1). */
    Optional<Payment> findByOrderId(Long orderId);

    /** PG 승인번호 기준 조회 (웹훅/승인 콜백 처리용). */
    Optional<Payment> findByPaymentUid(String paymentUid);

    /**
     * 만료 대상 결제 조회: 특정 상태이면서 만료 시각이 지난 건.
     * module-worker 가 타임아웃 결제를 EXPIRED 로 정리할 때 사용한다. (ADR-001)
     */
    List<Payment> findByStatusAndTimeoutAtBefore(PaymentStatus status, LocalDateTime time);
}
