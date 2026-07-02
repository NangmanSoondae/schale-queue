package com.schale.queue.api.payment;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.schale.queue.core.domain.NotFoundException;
import com.schale.queue.core.domain.payment.PaymentNotConfirmableException;
import com.schale.queue.core.domain.payment.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link PaymentController} 웹 슬라이스 테스트. 결제 확정의 HTTP 계약을 검증한다.
 *
 * <p>확정은 소유권 검증(리뷰 H3)을 위해 {@code X-Member-Id} 를 요구한다 — 헤더 누락은 400,
 * 결제 없음/타인 소유는 404(존재 은닉).
 */
@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    private static final Long ORDER_ID = 1001L;
    private static final Long MEMBER_ID = 42L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    @DisplayName("결제 확정은 200 과 PAID/COMPLETED 를 반환한다")
    void confirm_returns_200() throws Exception {
        willDoNothing().given(paymentService).confirm(eq(ORDER_ID), eq(MEMBER_ID), eq(null));

        mockMvc.perform(post("/api/v1/payments/1001/confirm").header("X-Member-Id", MEMBER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderId").value(1001))
            .andExpect(jsonPath("$.paymentStatus").value("PAID"))
            .andExpect(jsonPath("$.orderStatus").value("COMPLETED"));
    }

    @Test
    @DisplayName("X-Member-Id 헤더 없이는 400 MISSING_HEADER 로 거부된다(소유권 검증 전제, H3)")
    void confirm_requires_member_header() throws Exception {
        mockMvc.perform(post("/api/v1/payments/1001/confirm"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    @DisplayName("이미 확정/만료된 결제는 409 PAYMENT_NOT_CONFIRMABLE 를 반환한다")
    void confirm_returns_409_when_not_confirmable() throws Exception {
        willThrow(new PaymentNotConfirmableException("결제를 확정할 수 없는 상태입니다. status=EXPIRED"))
            .given(paymentService).confirm(eq(ORDER_ID), eq(MEMBER_ID), eq(null));

        mockMvc.perform(post("/api/v1/payments/1001/confirm").header("X-Member-Id", MEMBER_ID))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_NOT_CONFIRMABLE"));
    }

    @Test
    @DisplayName("존재하지 않거나 본인 소유가 아닌 결제는 404 NOT_FOUND 를 반환한다(존재 은닉)")
    void confirm_returns_404_when_not_found_or_not_owner() throws Exception {
        willThrow(new NotFoundException("결제가 존재하지 않습니다. orderId=1001"))
            .given(paymentService).confirm(eq(ORDER_ID), eq(MEMBER_ID), eq(null));

        mockMvc.perform(post("/api/v1/payments/1001/confirm").header("X-Member-Id", MEMBER_ID))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
