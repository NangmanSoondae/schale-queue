package com.schale.queue.api.payment;

import com.schale.queue.api.payment.dto.PaymentConfirmResponse;
import com.schale.queue.core.domain.payment.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 API. 실제 PG 연동 전이므로 확정은 <b>승인 성공 시뮬레이션</b>이다(UC-06).
 *
 * <p>확정 성공 시 재고가 {@code reserved→sold} 로 확정되고(P-S2) 주문이 COMPLETED 된다.
 * 결제 실패/만료 흐름(EXPIRED→재고 해제)은 만료 워커(UC-07, module-worker)가 담당한다.
 */
@Tag(name = "Payment", description = "결제 API (PG 승인 시뮬레이션)")
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(
        summary = "결제 확정",
        description = "PG 승인 성공을 시뮬레이션한다. READY 결제를 PAID 로 확정하고 재고를 판매 확정(reserved→sold)한다. "
            + "이미 확정/만료된 결제면 409."
    )
    @PostMapping("/{orderId}/confirm")
    public PaymentConfirmResponse confirm(@PathVariable Long orderId) {
        paymentService.confirm(orderId, null);
        return PaymentConfirmResponse.paid(orderId);
    }
}
