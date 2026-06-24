package com.schale.queue.worker.queue;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.schale.queue.core.domain.queue.AdmissionTokenService;
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
 * <p>Consumer 의 <b>오케스트레이션 규약</b>(활성 큐 발견 → batch 만큼 dequeue → 각자 입장 토큰 발급)을
 * 검증한다. Redis 거동(원자성/FIFO/정리)은 {@code QueueIntegrationTest} 가 실제 Redis 로 증명하므로,
 * 여기서는 협력 객체를 목으로 두고 호출 흐름만 빠르게 검증한다(인프라 비의존).
 */
@ExtendWith(MockitoExtension.class)
class QueueConsumerTest {

    @Mock
    private QueueService queueService;

    @Mock
    private AdmissionTokenService admissionTokenService;

    private QueueProperties properties;
    private QueueConsumer consumer;

    @BeforeEach
    void setUp() {
        properties = new QueueProperties();
        properties.getConsumer().setBatchSize(50);
        consumer = new QueueConsumer(queueService, admissionTokenService, properties);
    }

    @Test
    @DisplayName("활성 큐를 batch 만큼 dequeue 해 꺼낸 회원 각각에게 입장 토큰을 발급한다")
    void drains_active_goods_and_issues_token_per_member() {
        // given — goodsId=1 큐에 10, 11 번이 대기
        when(queueService.activeGoods()).thenReturn(Set.of(1L));
        when(queueService.dequeue(1L, 50)).thenReturn(List.of(10L, 11L));
        when(admissionTokenService.issue(anyLong(), anyLong())).thenReturn(true);

        // when
        consumer.drain();

        // then — batchSize 로 dequeue 하고, 꺼낸 회원마다 issue 호출
        verify(queueService).dequeue(1L, 50);
        verify(admissionTokenService).issue(1L, 10L);
        verify(admissionTokenService).issue(1L, 11L);
    }

    @Test
    @DisplayName("활성 큐가 없으면 dequeue·발급을 시도하지 않는다(헛돌이 방지)")
    void skips_when_no_active_goods() {
        // given — 활성 큐 없음
        when(queueService.activeGoods()).thenReturn(Set.of());

        // when
        consumer.drain();

        // then — 소비/발급 일절 없음
        verify(queueService, never()).dequeue(anyLong(), anyLong());
        verifyNoInteractions(admissionTokenService);
    }
}
