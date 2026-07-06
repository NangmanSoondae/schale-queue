package com.schale.queue.worker.queue;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.schale.queue.core.domain.queue.QueueProperties;
import com.schale.queue.core.domain.queue.QueueService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 대기열 소비 Consumer 단위 테스트.
 *
 * <p>Consumer 의 <b>오케스트레이션 규약</b>(활성 큐 발견 → batch 만큼 원자 소비+발급 → 상품 간 장애 격리)을
 * 검증한다. Redis 거동(원자성/FIFO/토큰 발급/정리)은 {@code QueueIntegrationTest} 가 실제 Redis 로
 * 증명하므로, 여기서는 협력 객체를 목으로 두고 호출 흐름만 빠르게 검증한다(인프라 비의존).
 */
@ExtendWith(MockitoExtension.class)
class QueueConsumerTest {

    @Mock
    private QueueService queueService;

    private QueueProperties properties;
    private QueueConsumer consumer;

    @BeforeEach
    void setUp() {
        properties = new QueueProperties();
        properties.getConsumer().setBatchSize(50);
        consumer = new QueueConsumer(queueService, properties,
            new com.schale.queue.worker.health.WorkerLiveness(java.time.Clock.systemUTC()));
    }

    @Test
    @DisplayName("활성 큐마다 batch 만큼 dequeueAndAdmit(원자 소비+발급)을 호출한다")
    void drains_active_goods_with_batch_size() {
        // given — goodsId=1 큐에 10, 11 번이 대기
        when(queueService.activeGoods()).thenReturn(Set.of(1L));
        when(queueService.dequeueAndAdmit(1L, 50))
            .thenReturn(new QueueService.AdmitResult(List.of(10L, 11L), 2L));

        // when
        consumer.drain();

        // then — batchSize 로 원자 소비+발급 1회 (별도 발급 호출 없음 — 스크립트에 내장)
        verify(queueService).dequeueAndAdmit(1L, 50);
    }

    @Test
    @DisplayName("활성 큐가 없으면 소비를 시도하지 않는다(헛돌이 방지)")
    void skips_when_no_active_goods() {
        // given — 활성 큐 없음
        when(queueService.activeGoods()).thenReturn(Set.of());

        // when
        consumer.drain();

        // then — 소비 일절 없음
        verify(queueService, never()).dequeueAndAdmit(anyLong(), anyLong());
    }

    @Test
    @DisplayName("한 상품의 소비 실패는 격리되어 나머지 상품 소비를 막지 않는다")
    void failure_on_one_goods_does_not_block_others() {
        // given — 두 상품이 활성, 그중 하나는 소비가 실패한다
        when(queueService.activeGoods()).thenReturn(Set.of(1L, 2L));
        when(queueService.dequeueAndAdmit(anyLong(), eq(50L)))
            .thenThrow(new RuntimeException("redis 순단"))
            .thenReturn(new QueueService.AdmitResult(List.of(10L), 1L));

        // when — 예외가 밖으로 새지 않는다
        consumer.drain();

        // then — 실패한 상품과 무관하게 두 상품 모두 소비가 시도된다
        verify(queueService).dequeueAndAdmit(1L, 50);
        verify(queueService).dequeueAndAdmit(2L, 50);
    }
}
