package com.schale.queue.api.queue;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.schale.queue.core.domain.NotFoundException;
import com.schale.queue.core.domain.goods.GoodsService;
import com.schale.queue.core.domain.goods.SaleNotOpenException;
import com.schale.queue.core.domain.queue.QueueService;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link QueueController} 웹 슬라이스 테스트. 진입(enqueue)과 SSE 구독의 HTTP 계약을 검증한다.
 */
@WebMvcTest(QueueController.class)
class QueueControllerTest {

    private static final String MEMBER_HEADER = "X-Member-Id";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QueueService queueService;

    @MockitoBean
    private QueueStreamService queueStreamService;

    // 판매 시작 게이트(UC-02). 기본 목은 아무것도 던지지 않음 = 게이트 통과.
    @MockitoBean
    private GoodsService goodsService;

    // 유령 엔트리 게이트(리뷰2 M-7). 기본 목은 false = 토큰 미보유(일반 진입).
    @MockitoBean
    private com.schale.queue.core.domain.queue.AdmissionTokenService admissionTokenService;

    @Test
    @DisplayName("대기열 진입은 201 Created 와 현재 순번/대기 인원을 반환한다")
    void enter_returns_201_with_position() throws Exception {
        // given
        given(queueService.getPosition(1L, 42L)).willReturn(OptionalLong.of(42));
        given(queueService.size(1L)).willReturn(1234L);

        // when & then
        mockMvc.perform(post("/api/v1/queue/1/entries").header(MEMBER_HEADER, 42L))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.position").value(42))
            .andExpect(jsonPath("$.waiting").value(1234));
        then(queueService).should().enqueue(1L, 42L);
    }

    @Test
    @DisplayName("진입 시 X-Member-Id 헤더가 없으면 400 MISSING_HEADER 를 반환한다")
    void enter_returns_400_when_member_header_missing() throws Exception {
        mockMvc.perform(post("/api/v1/queue/1/entries"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
        then(queueService).should(never()).enqueue(eq(1L), eq(42L));
    }

    @Test
    @DisplayName("판매 시작(openAt) 전 진입은 409 SALE_NOT_OPEN 으로 거부되고 큐에 넣지 않는다(UC-02)")
    void enter_returns_409_before_sale_opens() throws Exception {
        willThrow(new SaleNotOpenException("판매 시작 전입니다. goodsId=1"))
            .given(goodsService).checkSaleOpen(1L);

        mockMvc.perform(post("/api/v1/queue/1/entries").header(MEMBER_HEADER, 42L))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SALE_NOT_OPEN"));
        then(queueService).should(never()).enqueue(eq(1L), eq(42L));
    }

    @Test
    @DisplayName("존재하지 않는 상품 진입은 404 NOT_FOUND 로 거부된다(고아 큐 방지)")
    void enter_returns_404_for_unknown_goods() throws Exception {
        willThrow(new NotFoundException("상품이 존재하지 않습니다. goodsId=999"))
            .given(goodsService).checkSaleOpen(999L);

        mockMvc.perform(post("/api/v1/queue/999/entries").header(MEMBER_HEADER, 42L))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        then(queueService).should(never()).enqueue(eq(999L), eq(42L));
    }

    @Test
    @DisplayName("유효한 입장 토큰 보유자의 재진입은 큐에 넣지 않고 200/position=0 을 반환한다(리뷰2 M-7 유령 엔트리 방지)")
    void enter_skips_enqueue_for_admitted_member() throws Exception {
        // given — admitted 후 뒤로가기로 재진입한 회원(토큰 유효)
        given(admissionTokenService.hasValidToken(1L, 42L)).willReturn(true);
        given(queueService.size(1L)).willReturn(10L);

        // when & then — 재enqueue 없음(워커 admit 예산 소각·waiting 부풀림 차단), 입장 상태(0) 응답
        mockMvc.perform(post("/api/v1/queue/1/entries").header(MEMBER_HEADER, 42L))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.position").value(0));
        then(queueService).should(never()).enqueue(eq(1L), eq(42L));
    }

    @Test
    @DisplayName("순번 조회는 200 과 현재 순번/대기 인원을 반환한다")
    void position_returns_200_with_rank() throws Exception {
        // given
        given(queueService.getPosition(1L, 42L)).willReturn(OptionalLong.of(7));
        given(queueService.size(1L)).willReturn(99L);

        // when & then
        mockMvc.perform(get("/api/v1/queue/1/position").header(MEMBER_HEADER, 42L))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.position").value(7))
            .andExpect(jsonPath("$.waiting").value(99));
    }

    @Test
    @DisplayName("대기 중이 아니면 순번 조회는 position=0 을 반환한다")
    void position_returns_zero_when_not_waiting() throws Exception {
        // given — 큐에 없음
        given(queueService.getPosition(1L, 42L)).willReturn(OptionalLong.empty());
        given(queueService.size(1L)).willReturn(0L);

        // when & then
        mockMvc.perform(get("/api/v1/queue/1/position").header(MEMBER_HEADER, 42L))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.position").value(0));
    }

    @Test
    @DisplayName("SSE 구독은 비동기 스트림을 시작하고 스트림 서비스에 위임한다")
    void subscribe_starts_async_stream() throws Exception {
        // given — 스트림 서비스가 열린 emitter 를 반환
        given(queueStreamService.subscribe(1L, 42L)).willReturn(new SseEmitter());

        // when & then — SSE 는 비동기로 시작된다
        mockMvc.perform(get("/api/v1/queue/1/subscribe").header(MEMBER_HEADER, 42L))
            .andExpect(request().asyncStarted());
        then(queueStreamService).should().subscribe(1L, 42L);
    }
}
