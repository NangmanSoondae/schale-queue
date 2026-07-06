package com.schale.queue.worker.payment;

import com.schale.queue.core.domain.payment.PaymentService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결제 만료 정리 워커(UC-07). 결제 제한시간({@code Payment.timeoutAt})이 지난 READY 결제를 EXPIRED 로
 * 정리하고, 예약된 재고를 해제(reserved→available)하며 1인 한도 슬롯을 반납한다(P-S4/P-O3).
 *
 * <p>건별로 {@link PaymentService#expireOne} 을 호출한다(각자 독립 트랜잭션·멱등). 한 건이 실패해도
 * 다른 건의 정리를 막지 않는다. 결제 확정과의 경합은 PaymentService 의 비관적 락이 직렬화한다.
 */
@Component
@RequiredArgsConstructor
public class PaymentExpiryWorker {

    private static final Logger log = LoggerFactory.getLogger(PaymentExpiryWorker.class);

    private final PaymentService paymentService;
    private final Clock clock;
    private final com.schale.queue.worker.health.WorkerLiveness liveness;

    @Scheduled(fixedDelayString = "${schale.payment.expiry-interval:10s}")
    public void sweep() {
        // 생존 신고(리뷰2 H-2): 주기 10s + 최악 스윕 시간 여유 → 60s 못 돌아오면 wedge.
        liveness.beat("payment-expiry", java.time.Duration.ofSeconds(60));
        List<Long> dueOrderIds = paymentService.findDueOrderIds(LocalDateTime.now(clock));
        if (dueOrderIds.isEmpty()) {
            return;
        }
        int expired = 0;
        for (Long orderId : dueOrderIds) {
            try {
                paymentService.expireOne(orderId);
                expired++;
            } catch (RuntimeException e) {
                log.warn("결제 만료 처리 실패 orderId={}", orderId, e);
            }
        }
        log.info("결제 만료 정리 {}건 (대상 {}건)", expired, dueOrderIds.size());
    }
}
