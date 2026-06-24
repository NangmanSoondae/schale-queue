package com.schale.queue.worker.queue;

import com.schale.queue.core.domain.queue.AdmissionTokenService;
import com.schale.queue.core.domain.queue.QueueProperties;
import com.schale.queue.core.domain.queue.QueueService;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 대기열 소비 Consumer(Phase 3 ③). 트래픽 평탄화의 실행 주체다.
 *
 * <p>정해진 주기({@code schale.queue.consumer.interval})마다 활성 큐를 돌며,
 * 상품(goodsId)당 {@code batchSize} 명만 대기열 맨 앞에서 꺼내({@link QueueService#dequeue})
 * 입장 토큰을 발급한다({@link AdmissionTokenService#issue}). 이렇게 DB 로 향하는 입장 트래픽을
 * <b>처리율(rate) 이하로 묶어</b> 평탄화한다(P-Q4 ≤ NFR S3, docs/2_architecture.md §2.4 전략 2).
 *
 * <p>꺼내기(dequeue)는 Redis 단일 원자 연산이라 워커 인스턴스가 여럿이어도 한 회원이
 * 두 번 입장하지 않는다. 입장 토큰 발급은 {@code SET NX} 고정창이라(P-Q3) 재호출에도 안전하다.
 */
@Component
@RequiredArgsConstructor
public class QueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(QueueConsumer.class);

    private final QueueService queueService;
    private final AdmissionTokenService admissionTokenService;
    private final QueueProperties properties;

    /**
     * 한 tick 의 소비 사이클. 활성 큐를 모두 돌며 {@code batchSize} 만큼 입장 처리한다.
     *
     * <p>{@code fixedDelayString} 은 이전 실행 <b>종료 후</b> interval 만큼 쉬어, 한 사이클이
     * 길어져도 호출이 겹치지 않게 한다. interval 은 {@link QueueProperties.Consumer#getInterval()}
     * 과 동일한 프로퍼티 키를 읽는다.
     */
    @Scheduled(fixedDelayString = "${schale.queue.consumer.interval:200ms}")
    public void drain() {
        Set<Long> activeGoods = queueService.activeGoods();
        if (activeGoods.isEmpty()) {
            return;
        }
        int batchSize = properties.getConsumer().getBatchSize();
        for (Long goodsId : activeGoods) {
            int issued = admit(goodsId, batchSize);
            if (issued > 0) {
                log.debug("대기열 입장 처리 goodsId={} issued={}", goodsId, issued);
            }
        }
    }

    /** 상품 하나에 대해 최대 {@code batchSize} 명을 꺼내 입장 토큰을 발급하고, 신규 발급 수를 반환한다. */
    private int admit(Long goodsId, int batchSize) {
        List<Long> admitted = queueService.dequeue(goodsId, batchSize);
        int issued = 0;
        for (Long memberId : admitted) {
            if (admissionTokenService.issue(goodsId, memberId)) {
                issued++;
            }
        }
        return issued;
    }
}
