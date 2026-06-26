package com.schale.queue.api.queue;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
