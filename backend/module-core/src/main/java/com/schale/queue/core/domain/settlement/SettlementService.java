package com.schale.queue.core.domain.settlement;

import com.schale.queue.core.domain.order.event.OrderCancelledEvent;
import com.schale.queue.core.domain.order.event.OrderCompletedEvent;
import com.schale.queue.core.domain.settlement.repository.SettlementRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 도메인 서비스 — 주문 완료/취소 이벤트를 정산 원장에 반영한다(ADR-002 후방 컨슈머 확장).
 *
 * <p><b>적재({@link #settle}).</b> 주문 완료 이벤트의 총액에 플랫폼 수수료율을 적용해 정산 행을
 * {@code PENDING_PAYOUT} 으로 적재한다. 주문당 1정산이라 이미 정산된 주문이면 건너뛴다(아웃박스
 * at-least-once 재전달에 대한 도메인 차원의 2차 멱등 방어 — 컨슈머의 {@code processed_event} 가 1차).
 *
 * <p><b>반제({@link #reverse}).</b> 취소 이벤트가 오면 해당 주문의 정산 행을 {@code REVERSED} 로 돌린다.
 * 결제 만료처럼 <b>완료된 적 없는 주문</b>은 정산 행이 없으므로 <b>no-op</b> 이다(정산된 적 없는 주문은
 * 반제 대상이 아님 — 원장상 정확).
 *
 * <p>수수료율은 {@code schale.settlement.commission-rate}(기본 0.03)로 외부 주입한다(§5.4.1).
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementRepository settlementRepository;
    private final Clock clock;

    /** 플랫폼 수수료율([0,1]). 기본 3%. */
    @Value("${schale.settlement.commission-rate:0.03}")
    private double commissionRate;

    /**
     * 주문 완료 이벤트로부터 정산을 적재한다(PENDING_PAYOUT). 이미 정산된 주문이면 멱등하게 건너뛴다.
     */
    @Transactional
    public void settle(OrderCompletedEvent event) {
        if (settlementRepository.existsByOrderId(event.orderId())) {
            log.info("이미 정산된 주문 무시 orderId={} eventId={}", event.orderId(), event.eventId());
            return;
        }
        Settlement settlement = Settlement.settle(
            event.orderId(), event.memberId(), event.totalAmount(),
            commissionRate, LocalDateTime.now(clock));
        settlementRepository.save(settlement);
        log.info("정산 적재 orderId={} gross={} fee={} net={} (rate={})",
            event.orderId(), settlement.getGrossAmount(), settlement.getFeeAmount(),
            settlement.getNetAmount(), commissionRate);
    }

    /**
     * 주문 취소 이벤트로 정산을 반제한다(REVERSED). 정산 행이 없는 주문(예: 결제 만료)은 no-op.
     */
    @Transactional
    public void reverse(OrderCancelledEvent event) {
        settlementRepository.findByOrderId(event.orderId()).ifPresentOrElse(
            settlement -> {
                settlement.reverse();
                log.info("정산 반제 orderId={} reason={} net={}",
                    event.orderId(), event.reason(), settlement.getNetAmount());
            },
            () -> log.info("정산 없음 — 반제 건너뜀 orderId={} reason={} (미정산 주문)",
                event.orderId(), event.reason()));
    }
}
