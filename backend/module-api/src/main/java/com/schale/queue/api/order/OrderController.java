package com.schale.queue.api.order;

import com.schale.queue.api.order.dto.CreateOrderRequest;
import com.schale.queue.api.order.dto.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 API. 입장 토큰(P-O1)을 통과한 회원만 주문을 생성할 수 있다.
 */
@Tag(name = "Order", description = "주문 API (입장 토큰 보유자 전용)")
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderFacade orderFacade;

    @Operation(
        summary = "주문 생성",
        description = "유효한 입장 토큰 보유자만 주문할 수 있다(P-O1). 토큰은 주문 시 1회 원자적으로 소비된다. "
            + "회원 식별은 X-Member-Id 헤더로 전달한다."
    )
    @PostMapping
    public ResponseEntity<OrderResponse> create(
        @RequestHeader("X-Member-Id") Long memberId,
        @Valid @RequestBody CreateOrderRequest request
    ) {
        OrderResponse response = orderFacade.placeOrder(memberId, request.goodsId(), request.quantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
