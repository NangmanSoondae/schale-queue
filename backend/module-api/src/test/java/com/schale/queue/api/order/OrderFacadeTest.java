package com.schale.queue.api.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.schale.queue.api.order.dto.OrderResponse;
import com.schale.queue.core.domain.order.Order;
import com.schale.queue.core.domain.order.OrderService;
import com.schale.queue.core.domain.order.OrderStatus;
import com.schale.queue.core.domain.order.PurchaseLimitExceededException;
import com.schale.queue.core.domain.queue.AdmissionTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OrderFacade} 입장 게이트 단위 테스트.
 *
 * <p>핵심 정책을 검증한다: ① 토큰을 원자적으로 소비(revoke)한 뒤에만 주문이 진행된다,
 * ② 소비 실패면 주문을 호출하지 않고 거부한다, ③ 비즈니스 거부는 토큰을 재발급하지 않고,
 * ④ 시스템 오류만 토큰을 재발급한다.
 */
@ExtendWith(MockitoExtension.class)
class OrderFacadeTest {

    private static final Long GOODS_ID = 1L;
    private static final Long MEMBER_ID = 42L;
    private static final int QUANTITY = 1;

    @Mock
    private AdmissionTokenService admissionTokenService;

    @Mock
    private OrderService orderService;

    @InjectMocks
    private OrderFacade orderFacade;

    @Test
    @DisplayName("토큰 소비 성공 시 주문을 생성하고 응답을 반환한다")
    void places_order_when_admission_consumed() {
        // given — 토큰이 존재해 revoke 가 소비에 성공
        given(admissionTokenService.revoke(GOODS_ID, MEMBER_ID)).willReturn(true);
        Order created = Order.builder()
            .memberId(MEMBER_ID)
            .orderStatus(OrderStatus.PENDING)
            .totalAmount(49_000L)
            .build();
        given(orderService.createOrder(MEMBER_ID, GOODS_ID, QUANTITY)).willReturn(created);

        // when
        OrderResponse response = orderFacade.placeOrder(MEMBER_ID, GOODS_ID, QUANTITY);

        // then — 주문이 생성되고, 재발급(issue)은 호출되지 않는다
        assertThat(response.orderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.totalAmount()).isEqualTo(49_000L);
        then(orderService).should().createOrder(MEMBER_ID, GOODS_ID, QUANTITY);
        then(admissionTokenService).should(never()).issue(GOODS_ID, MEMBER_ID);
    }

    @Test
    @DisplayName("토큰 소비 실패 시 주문을 호출하지 않고 입장 거부 예외를 던진다")
    void rejects_when_no_admission_token() {
        // given — 토큰이 없어 revoke 가 false
        given(admissionTokenService.revoke(GOODS_ID, MEMBER_ID)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> orderFacade.placeOrder(MEMBER_ID, GOODS_ID, QUANTITY))
            .isInstanceOf(AdmissionRequiredException.class);
        then(orderService).should(never()).createOrder(MEMBER_ID, GOODS_ID, QUANTITY);
    }

    @Test
    @DisplayName("비즈니스 거부(재고 부족)는 토큰을 재발급하지 않는다 (입장 턴 소진)")
    void does_not_reissue_on_business_rejection() {
        // given — 소비 성공 후 주문이 재고 부족(IllegalStateException)으로 실패
        given(admissionTokenService.revoke(GOODS_ID, MEMBER_ID)).willReturn(true);
        given(orderService.createOrder(MEMBER_ID, GOODS_ID, QUANTITY))
            .willThrow(new IllegalStateException("재고 부족"));

        // when & then — 예외는 전파되고, 토큰은 소진된 채 둔다
        assertThatThrownBy(() -> orderFacade.placeOrder(MEMBER_ID, GOODS_ID, QUANTITY))
            .isInstanceOf(IllegalStateException.class);
        then(admissionTokenService).should(never()).issue(GOODS_ID, MEMBER_ID);
    }

    @Test
    @DisplayName("1인 한도 초과(P-O3)는 비즈니스 거부이므로 토큰을 재발급하지 않는다")
    void does_not_reissue_on_purchase_limit() {
        // given — 소비 성공 후 주문이 한도 초과로 실패
        given(admissionTokenService.revoke(GOODS_ID, MEMBER_ID)).willReturn(true);
        given(orderService.createOrder(MEMBER_ID, GOODS_ID, QUANTITY))
            .willThrow(new PurchaseLimitExceededException("한도 초과"));

        // when & then — 예외 전파, 토큰은 소진 유지(재발급 없음)
        assertThatThrownBy(() -> orderFacade.placeOrder(MEMBER_ID, GOODS_ID, QUANTITY))
            .isInstanceOf(PurchaseLimitExceededException.class);
        then(admissionTokenService).should(never()).issue(GOODS_ID, MEMBER_ID);
    }

    @Test
    @DisplayName("시스템 오류(인프라 장애)는 토큰을 best-effort 재발급한다")
    void reissues_on_system_error() {
        // given — 소비 성공 후 주문이 시스템 오류로 실패
        given(admissionTokenService.revoke(GOODS_ID, MEMBER_ID)).willReturn(true);
        given(orderService.createOrder(MEMBER_ID, GOODS_ID, QUANTITY))
            .willThrow(new RuntimeException("DB 연결 끊김"));

        // when & then — 예외는 전파되고, 토큰을 재발급한다
        assertThatThrownBy(() -> orderFacade.placeOrder(MEMBER_ID, GOODS_ID, QUANTITY))
            .isInstanceOf(RuntimeException.class);
        then(admissionTokenService).should().issue(GOODS_ID, MEMBER_ID);
    }
}
