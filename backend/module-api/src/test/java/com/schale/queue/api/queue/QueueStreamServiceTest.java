package com.schale.queue.api.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.schale.queue.api.queue.SubscriptionRegistry.Subscription;
import com.schale.queue.core.domain.queue.AdmissionTokenService;
import com.schale.queue.core.domain.queue.QueueService;
import com.schale.queue.core.domain.queue.QueueService.QueueSnapshot;
import java.io.IOException;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
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
 * broadcast 는 상품별 벌크 조회(순번 스냅샷 → 토큰 MGET, 상품군 가상 스레드 팬아웃) 경로다
 * (리뷰 M8 → 리뷰2 M-4/5/6, L-1/4).
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
    @DisplayName("대기 중이면 스냅샷의 순번·총원(동일 왕복, M-6)으로 position 을 전송하고 구독을 유지한다")
    void broadcasts_position_when_waiting() throws IOException {
        given(queueService.getPositionsSnapshot(eq(GOODS_ID), any()))
            .willReturn(new QueueSnapshot(Map.of(MEMBER_ID, 5L), 100L));
        given(admissionTokenService.membersWithValidToken(eq(GOODS_ID), any())).willReturn(Set.of());

        streamService.broadcast();

        then(emitter).should().send(any(SseEmitter.SseEventBuilder.class));
        then(emitter).should(never()).complete();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("입장 토큰이 발급되면 admitted 전송 후 스트림을 종료하고 구독을 제거한다")
    void broadcasts_admitted_and_completes_when_token_present() throws IOException {
        given(queueService.getPositionsSnapshot(eq(GOODS_ID), any()))
            .willReturn(new QueueSnapshot(Map.of(), 0L));
        given(admissionTokenService.membersWithValidToken(eq(GOODS_ID), any())).willReturn(Set.of(MEMBER_ID));

        streamService.broadcast();

        then(emitter).should().send(any(SseEmitter.SseEventBuilder.class));
        then(emitter).should().complete();
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("admitted 는 소유권(레지스트리 제거) 선점자만 전송한다 — 이중 전송 차단(리뷰2 M-4)")
    void admitted_sent_only_by_ownership_claimer() throws IOException {
        // when — 같은 (key, emitter) 로 두 경로가 연달아 시도(병행 경합의 직렬화된 등가물):
        streamService.sendAdmitted(KEY, emitter);   // 1차: remove 성공 → 전송
        streamService.sendAdmitted(KEY, emitter);   // 2차: remove 실패(이미 제거) → 침묵

        // then — send/complete 는 정확히 1회
        then(emitter).should(times(1)).send(any(SseEmitter.SseEventBuilder.class));
        then(emitter).should(times(1)).complete();
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("대기열에도 없고 토큰도 없으면(전이 중/이탈) 이번 tick 은 아무것도 보내지 않는다")
    void skips_when_neither_waiting_nor_admitted() throws IOException {
        given(queueService.getPositionsSnapshot(eq(GOODS_ID), any()))
            .willReturn(new QueueSnapshot(Map.of(), 0L));
        given(admissionTokenService.membersWithValidToken(eq(GOODS_ID), any())).willReturn(Set.of());

        streamService.broadcast();

        then(emitter).should(never()).send(any(SseEmitter.SseEventBuilder.class));
        then(emitter).should(never()).complete();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("position 전송이 IOException(클라이언트 끊김)이면 구독을 조용히 정리한다")
    void cleans_up_subscription_on_send_failure() throws IOException {
        given(queueService.getPositionsSnapshot(eq(GOODS_ID), any()))
            .willReturn(new QueueSnapshot(Map.of(MEMBER_ID, 7L), 50L));
        given(admissionTokenService.membersWithValidToken(eq(GOODS_ID), any())).willReturn(Set.of());
        org.mockito.BDDMockito.willThrow(new IOException("broken pipe"))
            .given(emitter).send(any(SseEmitter.SseEventBuilder.class));

        streamService.broadcast();

        assertThat(registry.size()).isZero();
        then(emitter).should(never()).completeWithError(any());   // 끊긴 클라이언트엔 통지 무의미
    }

    @Test
    @DisplayName("position 전송이 내부 오류(RuntimeException)면 completeWithError 로 닫아 좀비 연결을 막는다(리뷰2 M-3)")
    void closes_stream_with_error_on_internal_send_failure() throws IOException {
        given(queueService.getPositionsSnapshot(eq(GOODS_ID), any()))
            .willReturn(new QueueSnapshot(Map.of(MEMBER_ID, 7L), 50L));
        given(admissionTokenService.membersWithValidToken(eq(GOODS_ID), any())).willReturn(Set.of());
        org.mockito.BDDMockito.willThrow(new IllegalStateException("serialization"))
            .given(emitter).send(any(SseEmitter.SseEventBuilder.class));

        streamService.broadcast();

        assertThat(registry.size()).isZero();
        then(emitter).should().completeWithError(any(IllegalStateException.class));
    }

    @Test
    @DisplayName("순번 조회가 실패해도 입장 통지는 진행된다 — 부분 실패 격리(리뷰2 L-4)")
    void delivers_admitted_even_when_position_lookup_fails() throws IOException {
        given(queueService.getPositionsSnapshot(eq(GOODS_ID), any()))
            .willThrow(new IllegalStateException("redis pipeline down"));
        given(admissionTokenService.membersWithValidToken(eq(GOODS_ID), any())).willReturn(Set.of(MEMBER_ID));

        streamService.broadcast();

        then(emitter).should().send(any(SseEmitter.SseEventBuilder.class));
        then(emitter).should().complete();
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("토큰 조회가 실패해도 순번 갱신은 진행된다(ZSET 재적 회원은 정의상 미입장이라 안전)")
    void delivers_position_even_when_token_lookup_fails() throws IOException {
        given(queueService.getPositionsSnapshot(eq(GOODS_ID), any()))
            .willReturn(new QueueSnapshot(Map.of(MEMBER_ID, 5L), 100L));
        given(admissionTokenService.membersWithValidToken(eq(GOODS_ID), any()))
            .willThrow(new IllegalStateException("redis mget down"));

        streamService.broadcast();

        then(emitter).should().send(any(SseEmitter.SseEventBuilder.class));
        then(emitter).should(never()).complete();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("양쪽 벌크 조회가 모두 실패하면 해당 상품 그룹만 이번 tick 을 건너뛰고 구독을 유지한다")
    void skips_goods_group_when_bulk_lookup_fails() throws IOException {
        given(queueService.getPositionsSnapshot(eq(GOODS_ID), any()))
            .willThrow(new IllegalStateException("redis down"));
        given(admissionTokenService.membersWithValidToken(eq(GOODS_ID), any()))
            .willThrow(new IllegalStateException("redis down"));

        streamService.broadcast();

        then(emitter).should(never()).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(registry.size()).isEqualTo(1);
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
