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
import com.schale.queue.core.domain.NotFoundException;
import com.schale.queue.core.domain.goods.SaleNotOpenException;
import com.schale.queue.core.domain.order.OrderStatus;
import com.schale.queue.core.domain.order.PurchaseLimitExceededException;
import com.schale.queue.core.domain.stock.InsufficientStockException;
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
        // given — 품절은 전용 예외로만 표현된다(리뷰 M3 — 범용 IllegalStateException 매핑 제거)
        given(orderFacade.placeOrder(eq(42L), eq(1L), anyInt()))
            .willThrow(new InsufficientStockException("잔여 재고가 부족합니다."));
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
    @DisplayName("판매 시작 전 주문은 409 SALE_NOT_OPEN 을 반환한다(UC-02)")
    void create_returns_409_before_sale_opens() throws Exception {
        given(orderFacade.placeOrder(eq(42L), eq(1L), anyInt()))
            .willThrow(new SaleNotOpenException("판매 시작 전입니다. goodsId=1"));
        String body = objectMapper.writeValueAsString(new TestRequest(1L, 1));

        mockMvc.perform(post("/api/v1/orders")
                .header(MEMBER_HEADER, 42L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SALE_NOT_OPEN"));
    }

    @Test
    @DisplayName("존재하지 않는 상품 주문은 404 NOT_FOUND 를 반환한다(과거 400 — REST 시맨틱 교정)")
    void create_returns_404_for_unknown_goods() throws Exception {
        given(orderFacade.placeOrder(eq(42L), eq(999L), anyInt()))
            .willThrow(new NotFoundException("상품이 존재하지 않습니다. goodsId=999"));
        String body = objectMapper.writeValueAsString(new TestRequest(999L, 1));

        mockMvc.perform(post("/api/v1/orders")
                .header(MEMBER_HEADER, 42L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("깨진 JSON 본문도 ErrorResponse 계약(400 MALFORMED_BODY)으로 응답한다(리뷰 M4)")
    void create_returns_400_on_malformed_json() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                .header(MEMBER_HEADER, 42L)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not-json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MALFORMED_BODY"));
        then(orderFacade).should(never()).placeOrder(eq(42L), eq(1L), anyInt());
    }

    @Test
    @DisplayName("숫자가 아닌 X-Member-Id 도 ErrorResponse 계약(400 INVALID_TYPE)으로 응답한다(리뷰 M4)")
    void create_returns_400_on_non_numeric_member_header() throws Exception {
        String body = objectMapper.writeValueAsString(new TestRequest(1L, 1));

        mockMvc.perform(post("/api/v1/orders")
                .header(MEMBER_HEADER, "abc")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_TYPE"));
    }

    @Test
    @DisplayName("매핑되지 않은 예외(인프라 장애)는 내부를 노출하지 않는 500 INTERNAL_ERROR 로 응답한다(리뷰 M4)")
    void create_returns_500_on_unexpected_error() throws Exception {
        given(orderFacade.placeOrder(eq(42L), eq(1L), anyInt()))
            .willThrow(new RuntimeException("DB 커넥션 풀 고갈 — 내부 상세 메시지"));
        String body = objectMapper.writeValueAsString(new TestRequest(1L, 1));

        mockMvc.perform(post("/api/v1/orders")
                .header(MEMBER_HEADER, 42L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            // 내부 메시지(커넥션 풀 등)가 응답에 새지 않아야 한다(§5.3.2)
            .andExpect(jsonPath("$.message").value("일시적인 서버 오류입니다. 잠시 후 다시 시도해 주세요."));
    }

    @Test
    @DisplayName("1인 구매 한도 초과는 409 Conflict 와 PURCHASE_LIMIT_EXCEEDED 코드를 반환한다")
    void create_returns_409_when_purchase_limit_exceeded() throws Exception {
        // given
        given(orderFacade.placeOrder(eq(42L), eq(1L), anyInt()))
            .willThrow(new PurchaseLimitExceededException("1인 구매 한도를 초과했습니다."));
        String body = objectMapper.writeValueAsString(new TestRequest(1L, 1));

        // when & then
        mockMvc.perform(post("/api/v1/orders")
                .header(MEMBER_HEADER, 42L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PURCHASE_LIMIT_EXCEEDED"));
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
