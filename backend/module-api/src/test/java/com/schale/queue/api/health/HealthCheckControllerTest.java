package com.schale.queue.api.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@link HealthCheckController} BDD 스타일 슬라이스 테스트.
 *
 * <p>MockMvc 로 웹 계층만 띄우고, {@link HealthService} 는 {@code @MockitoBean}(Spring Boot 3.4+
 * 모던 방식)으로 대체한다. {@code BDDMockito.given()} 으로 스텁을 구성하고, {@code AssertJ}
 * 메서드 체이닝으로 검증하여 Given-When-Then 흐름이 코드에 그대로 드러나도록 작성했다.
 */
@WebMvcTest(HealthCheckController.class)
class HealthCheckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private HealthService healthService;

    @Test
    @DisplayName("GET /api/v1/health 는 가동 메시지와 서버 시간을 200 OK 로 반환한다")
    void health_returns_running_message_and_server_time() throws Exception {
        // given — 서비스가 고정된 메시지와 시각을 반환하도록 스텁
        LocalDateTime fixedTime = LocalDateTime.of(2026, 6, 19, 16, 0, 0);
        HealthResponse stubbed = new HealthResponse("Schale Queue Server is Running!", fixedTime);
        given(healthService.check()).willReturn(stubbed);

        // when — 헬스 체크 엔드포인트 호출
        MvcResult result = mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andReturn();

        // then — 응답 본문을 역직렬화해 AssertJ 체이닝으로 검증
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        HealthResponse response = objectMapper.readValue(body, HealthResponse.class);

        assertThat(response).isNotNull();
        assertThat(response.message())
            .isNotBlank()
            .isEqualTo("Schale Queue Server is Running!");
        assertThat(response.serverTime()).isEqualTo(fixedTime);

        // and — 서비스가 정확히 한 번 호출되었는지 확인
        then(healthService).should().check();
    }
}
