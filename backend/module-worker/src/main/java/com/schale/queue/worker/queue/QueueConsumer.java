package com.schale.queue.worker.queue;

import com.schale.queue.core.domain.queue.QueueProperties;
import com.schale.queue.core.domain.queue.QueueService;
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
 * 상품(goodsId)당 {@code batchSize} 명만 대기열 맨 앞에서 꺼내 입장 토큰을 발급한다
 * ({@link QueueService#dequeueAndAdmit} — pop 과 토큰 발급이 <b>하나의 Redis 원자 연산</b>이라,
 * 이 사이에서 워커가 죽어도 "큐에서 빠졌는데 토큰이 없는" 회원이 생기지 않는다. 2026-07-02 리뷰 H2).
 * 이렇게 DB 로 향하는 입장 트래픽을 <b>처리율(rate) 이하로 묶어</b> 평탄화한다
 * (P-Q4 ≤ NFR S3, docs/2_architecture.md §2.4 전략 2).
 *
 * <p>꺼내기+발급은 Redis 단일 원자 연산이라 워커 인스턴스가 여럿이어도 한 회원이
 * 두 번 입장하지 않는다. 토큰은 {@code SET NX} 고정창이라(P-Q3) 재진입자의 기존 TTL 을 보존한다.
 */
@Component
@RequiredArgsConstructor
public class QueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(QueueConsumer.class);

    private final QueueService queueService;
    private final QueueProperties properties;

    /**
     * 한 tick 의 소비 사이클. 활성 큐를 모두 돌며 {@code batchSize} 만큼 입장 처리한다.
     *
     * <p>{@code fixedDelayString} 은 이전 실행 <b>종료 후</b> interval 만큼 쉬어, 한 사이클이
     * 길어져도 호출이 겹치지 않게 한다. interval 은 {@link QueueProperties.Consumer#getInterval()}
     * 과 동일한 프로퍼티 키를 읽는다.
     *
     * <p>상품 하나의 소비 실패(Redis 순단 등)는 해당 상품만 다음 tick 으로 미루고 <b>나머지 상품은
     * 계속 진행</b>한다 — 예외가 루프를 끊으면 한 상품 장애가 전 상품 입장 정지로 번진다(리뷰 H2 부속).
     */
    @Scheduled(fixedDelayString = "${schale.queue.consumer.interval:200ms}")
    public void drain() {
        Set<Long> activeGoods = queueService.activeGoods();
        if (activeGoods.isEmpty()) {
            return;
        }
        int batchSize = properties.getConsumer().getBatchSize();
        for (Long goodsId : activeGoods) {
            try {
                QueueService.AdmitResult result = queueService.dequeueAndAdmit(goodsId, batchSize);
                if (!result.members().isEmpty()) {
                    log.debug("대기열 입장 처리 goodsId={} admitted={} newTokens={}",
                        goodsId, result.members().size(), result.issued());
                }
            } catch (Exception e) {
                log.error("대기열 소비 실패 goodsId={} → 다음 tick 재시도 (다른 상품은 계속 진행)", goodsId, e);
            }
        }
    }
}
