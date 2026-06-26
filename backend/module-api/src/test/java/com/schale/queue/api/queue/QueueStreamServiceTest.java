package com.schale.queue.api.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.schale.queue.api.queue.SubscriptionRegistry.Subscription;
import com.schale.queue.core.domain.queue.AdmissionTokenService;
import com.schale.queue.core.domain.queue.QueueService;
import java.io.IOException;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link QueueStreamService} 폴링 로직 단위 테스트.
 *
 * <p>실제 {@link SubscriptionRegistry} 에 mock emitter 를 심어두고 {@link QueueStreamService#broadcast()}
 * 를 직접 호출해, 입장/대기/이탈 각 분기에서 올바른 이벤트 전송·정리가 일어나는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class QueueStreamServiceTest {

    private static final Long GOODS_ID = 1L;
    private static final Long MEMBER_ID = 42L;
    private static final Subscription KEY = new Subscription(GOODS_ID, MEMBER_ID);

    @Mock
    private QueueService queueService;

    @Mock
    private AdmissionTokenService admissionTokenService;

    @Mock
    private SseEmitter emitter;

    private SubscriptionRegistry registry;
    private QueueStreamService streamService;

    @BeforeEach
    void setUp() {
        registry = new SubscriptionRegistry();
        streamService = new QueueStreamService(
            queueService, admissionTokenService, registry, new QueueSseProperties());
        registry.add(KEY, emitter);
    }

    @Test
    @DisplayName("대기 중이면 position 이벤트를 전송하고 구독을 유지한다")
    void broadcasts_position_when_waiting() throws IOException {
        // given — 토큰 없음, 순번 5, 총 대기 100
        given(admissionTokenService.hasValidToken(GOODS_ID, MEMBER_ID)).willReturn(false);
        given(queueService.getPosition(GOODS_ID, MEMBER_ID)).willReturn(OptionalLong.of(5));
        given(queueService.size(GOODS_ID)).willReturn(100L);

        // when
        streamService.broadcast();

        // then — position 1회 전송, 종료하지 않고 구독 유지
        then(emitter).should().send(any(SseEmitter.SseEventBuilder.class));
        then(emitter).should(never()).complete();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("입장 토큰이 발급되면 admitted 전송 후 스트림을 종료하고 구독을 제거한다")
    void broadcasts_admitted_and_completes_when_token_present() throws IOException {
        // given — 토큰 보유
        given(admissionTokenService.hasValidToken(GOODS_ID, MEMBER_ID)).willReturn(true);

        // when
        streamService.broadcast();

        // then — admitted 1회 전송 + complete + 레지스트리에서 제거. 순번 조회는 하지 않는다.
        then(emitter).should().send(any(SseEmitter.SseEventBuilder.class));
        then(emitter).should().complete();
        then(queueService).should(never()).getPosition(GOODS_ID, MEMBER_ID);
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("대기열에도 없고 토큰도 없으면(전이 중/이탈) 이번 tick 은 아무것도 보내지 않는다")
    void skips_when_neither_waiting_nor_admitted() throws IOException {
        // given — 토큰 없음, 순번 없음
        given(admissionTokenService.hasValidToken(GOODS_ID, MEMBER_ID)).willReturn(false);
        given(queueService.getPosition(GOODS_ID, MEMBER_ID)).willReturn(OptionalLong.empty());

        // when
        streamService.broadcast();

        // then — 전송/종료 없음, 구독은 유지(타임아웃이 정리)
        then(emitter).should(never()).send(any(SseEmitter.SseEventBuilder.class));
        then(emitter).should(never()).complete();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("전송이 IOException(클라이언트 끊김)이면 구독을 조용히 정리한다")
    void cleans_up_subscription_on_send_failure() throws IOException {
        // given — 대기 중인데 send 가 실패
        given(admissionTokenService.hasValidToken(GOODS_ID, MEMBER_ID)).willReturn(false);
        given(queueService.getPosition(GOODS_ID, MEMBER_ID)).willReturn(OptionalLong.of(7));
        given(queueService.size(GOODS_ID)).willReturn(50L);
        org.mockito.BDDMockito.willThrow(new IOException("broken pipe"))
            .given(emitter).send(any(SseEmitter.SseEventBuilder.class));

        // when
        streamService.broadcast();

        // then — 구독이 제거된다
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("subscribe 는 emitter 를 만들어 레지스트리에 등록하고 반환한다")
    void subscribe_registers_and_returns_emitter() {
        // given — 신규 구독(초기 push 에서 토큰 없음·대기 없음으로 무전송)
        registry.remove(KEY, emitter);  // setUp 의 mock 구독 제거 후 실제 subscribe 검증
        given(admissionTokenService.hasValidToken(GOODS_ID, MEMBER_ID)).willReturn(false);
        given(queueService.getPosition(GOODS_ID, MEMBER_ID)).willReturn(OptionalLong.empty());

        // when
        SseEmitter created = streamService.subscribe(GOODS_ID, MEMBER_ID);

        // then
        assertThat(created).isNotNull();
        assertThat(registry.size()).isEqualTo(1);
    }
}
