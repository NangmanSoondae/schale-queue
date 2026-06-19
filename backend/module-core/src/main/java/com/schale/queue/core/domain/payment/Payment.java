package com.schale.queue.core.domain.payment;

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
 * 결제 엔티티.
 *
 * <p>외부 PG 연동의 변동성(실패·타임아웃·재시도)을 주문에서 격리하기 위해
 * {@code Order} 와 1:1 로 분리한다. {@code timeoutAt} 은 워커가 만료 결제를
 * 배치 정리하는 기준이 된다. (ADR-001 참조)
 */
@Getter
@Entity
@Table(name = "payment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 주문 FK (ID 참조, 1:1). */
    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    /** PG사 결제 승인번호. 승인 전에는 null. */
    @Column(name = "payment_uid", unique = true)
    private String paymentUid;

    /** 결제 금액 (KRW). */
    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    /** 결제 만료 시각. 경과 시 워커가 EXPIRED 처리. */
    @Column(name = "timeout_at")
    private LocalDateTime timeoutAt;

    @Builder
    private Payment(Long orderId, String paymentUid, Long amount, PaymentStatus status,
                    LocalDateTime timeoutAt) {
        this.orderId = orderId;
        this.paymentUid = paymentUid;
        this.amount = amount;
        this.status = status;
        this.timeoutAt = timeoutAt;
    }

    /** PG 승인 완료 처리. */
    public void approve(String paymentUid) {
        this.paymentUid = paymentUid;
        this.status = PaymentStatus.PAID;
    }

    public void changeStatus(PaymentStatus status) {
        this.status = status;
    }
}
