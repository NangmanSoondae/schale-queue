package com.schale.queue.api.order;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schale.queue.api.order.dto.OrderResponse;
import com.schale.queue.core.domain.order.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link OrderController} 웹 슬라이스 테스트. 입장 게이트의 HTTP 계약을 검증한다.
 *
 * <p>{@link OrderFacade} 는 {@code @MockitoBean} 으로 대체하고, 게이트/도메인 예외가
 * {@code GlobalExceptionHandler} 를 통해 의도한 상태 코드로 매핑되는지 확인한다.
 */
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final String MEMBER_HEADER = "X-Member-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderFacade orderFacade;

    @Test
    @DisplayName("입장 토큰 보유자의 정상 주문은 201 Created 와 주문 정보를 반환한다")
    void create_returns_201_when_admitted() throws Exception {
        // given
        given(orderFacade.placeOrder(eq(42L), eq(1L), anyInt()))
            .willReturn(new OrderResponse(1001L, OrderStatus.PENDING, 49_000L));
        String body = objectMapper.writeValueAsString(new TestRequest(1L, 2));

        // when & then
        mockMvc.perform(post("/api/v1/orders")
                .header(MEMBER_HEADER, 42L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.orderId").value(1001))
            .andExpect(jsonPath("$.orderStatus").value("PENDING"))
            .andExpect(jsonPath("$.totalAmount").value(49000));
    }

    @Test
    @DisplayName("입장 토큰이 없으면 403 Forbidden 과 ADMISSION_REQUIRED 코드를 반환한다")
    void create_returns_403_when_no_admission() throws Exception {
        // given
        given(orderFacade.placeOrder(eq(42L), eq(1L), anyInt()))
            .willThrow(new AdmissionRequiredException(1L, 42L));
        String body = objectMapper.writeValueAsString(new TestRequest(1L, 1));

        // when & then
        mockMvc.perform(post("/api/v1/orders")
                .header(MEMBER_HEADER, 42L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ADMISSION_REQUIRED"));
    }

    @Test
    @DisplayName("재고 부족은 409 Conflict 와 STOCK_CONFLICT 코드를 반환한다")
    void create_returns_409_when_out_of_stock() throws Exception {
        // given
        given(orderFacade.placeOrder(eq(42L), eq(1L), anyInt()))
            .willThrow(new IllegalStateException("잔여 재고가 부족합니다."));
        String body = objectMapper.writeValueAsString(new TestRequest(1L, 1));

        // when & then
        mockMvc.perform(post("/api/v1/orders")
                .header(MEMBER_HEADER, 42L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("STOCK_CONFLICT"));
    }

    @Test
    @DisplayName("수량이 0 이하면 검증 실패로 400 을 반환하고 주문을 호출하지 않는다")
    void create_returns_400_on_invalid_quantity() throws Exception {
        // given — quantity=0 (검증 위반)
        String body = objectMapper.writeValueAsString(new TestRequest(1L, 0));

        // when & then
        mockMvc.perform(post("/api/v1/orders")
                .header(MEMBER_HEADER, 42L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        then(orderFacade).should(never()).placeOrder(eq(42L), eq(1L), anyInt());
    }

    @Test
    @DisplayName("X-Member-Id 헤더가 없으면 400 과 MISSING_HEADER 코드를 반환한다")
    void create_returns_400_when_member_header_missing() throws Exception {
        // given
        String body = objectMapper.writeValueAsString(new TestRequest(1L, 1));

        // when & then — 헤더 누락
        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    /** 테스트 본문 직렬화용 보조 레코드(검증 위반 케이스를 만들기 위해 컨트롤러 DTO 와 분리). */
    private record TestRequest(Long goodsId, int quantity) {
    }
}
