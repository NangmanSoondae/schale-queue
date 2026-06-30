package com.schale.queue.core.domain.settlement;

import com.schale.queue.core.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 정산(Settlement) 원장 엔티티 — 주문 완료/취소 이벤트 구독으로 적재되는 수수료 차감 원장.
 *
 * <p><b>슬라이스 의의(ADR-002).</b> 발행측(주문/결제/아웃박스) 코드를 전혀 바꾸지 않고, 기존
 * {@code order.completed}/{@code order.cancelled} 토픽을 새 컨슈머 그룹으로 구독해 정산을 적재한다.
 * Kafka Pub/Sub 의 "신규 컨슈머 무변경 추가" 확장성을 실증한다.
 *
 * <p><b>금액 구조.</b> {@code grossAmount}(주문 총액)에서 플랫폼 수수료({@code feeAmount})를 차감한
 * {@code netAmount}(판매자 지급액)를 보관한다. 수수료율은 적재 시점에 도메인으로 주입된다.
 *
 * <p>도메인-발행 파이프라인 디커플 관례(아웃박스와 동일)에 따라 <b>FK 를 두지 않고</b> {@code orderId}/
 * {@code memberId} 를 값으로만 들고 있으며, 주문당 1정산을 {@code uk_settlement_order_id} 로 보장한다
 * (컨슈머 멱등의 2차 방어선).
 */
@Getter
@Entity
@Table(name = "settlement",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_settlement_order_id", columnNames = "order_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** 주문 총액(KRW). 정산의 기준 금액. */
    @Column(name = "gross_amount", nullable = false)
    private Long grossAmount;

    /** 플랫폼 수수료(KRW) = gross × commissionRate(버림). */
    @Column(name = "fee_amount", nullable = false)
    private Long feeAmount;

    /** 판매자 지급액(KRW) = gross − fee. */
    @Column(name = "net_amount", nullable = false)
    private Long netAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementStatus status;

    /** 정산 적재(PENDING_PAYOUT 전이) 시각. */
    @Column(name = "settled_at", nullable = false)
    private LocalDateTime settledAt;

    @Builder
    private Settlement(Long orderId, Long memberId, Long grossAmount, Long feeAmount,
                       Long netAmount, SettlementStatus status, LocalDateTime settledAt) {
        this.orderId = orderId;
        this.memberId = memberId;
        this.grossAmount = grossAmount;
        this.feeAmount = feeAmount;
        this.netAmount = netAmount;
        this.status = status;
        this.settledAt = settledAt;
    }

    /**
     * 주문 완료로부터 정산 행을 적재한다(PENDING_PAYOUT). 수수료는 {@code gross × rate} 를 버림(floor)해
     * 원 단위 정수로 만들고, 지급액은 그 차액이다.
     *
     * @param commissionRate 플랫폼 수수료율([0,1], 예 0.03). 음수/1 초과는 호출측에서 막는다.
     */
    public static Settlement settle(Long orderId, Long memberId, Long grossAmount,
                                    double commissionRate, LocalDateTime settledAt) {
        long fee = (long) Math.floor(grossAmount * commissionRate);
        return Settlement.builder()
            .orderId(orderId)
            .memberId(memberId)
            .grossAmount(grossAmount)
            .feeAmount(fee)
            .netAmount(grossAmount - fee)
            .status(SettlementStatus.PENDING_PAYOUT)
            .settledAt(settledAt)
            .build();
    }

    /** 완료됐던 정산을 반제(환불)한다({@code PENDING_PAYOUT→REVERSED}). 멱등하게 호출 가능. */
    public void reverse() {
        this.status = SettlementStatus.REVERSED;
    }
}
