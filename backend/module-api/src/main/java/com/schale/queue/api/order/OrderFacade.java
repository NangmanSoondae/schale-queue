package com.schale.queue.api.order;

import com.schale.queue.api.order.dto.OrderResponse;
import com.schale.queue.core.domain.order.Order;
import com.schale.queue.core.domain.order.OrderService;
import com.schale.queue.core.domain.order.PurchaseLimitExceededException;
import com.schale.queue.core.domain.queue.AdmissionTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 주문 입장 게이트 파사드(P-O1). 관문(module-api) 책임으로, 입장 토큰 검증과
 * 주문 생성({@link OrderService})의 순서를 조율한다. core 의 주문 도메인은 Redis 입장
 * 개념에 결합되지 않도록 순수하게 유지하고, 결합은 이 API 계층에서 닫는다.
 *
 * <p><b>원자적 소비 선행.</b> {@link AdmissionTokenService#revoke}(Redis {@code DEL})는 키가
 * 실제로 존재해 삭제됐을 때만 {@code true} 를 돌려준다. 이를 "입장 토큰의 단일 사용 소비"로
 * 활용하면, 같은 토큰으로 동시에 두 번 주문해도 {@code DEL} 이 단 하나의 요청에만 {@code true}
 * 를 반환하므로 <b>이중 주문 경합(TOCTOU)을 한 번의 원자 연산으로 차단</b>한다(정합성 우선 §5.1.3).
 *
 * <p><b>실패 보상.</b> 토큰을 소비한 뒤 주문이 실패하면:
 * <ul>
 *   <li><b>비즈니스 거부</b>(품절·잘못된 요청 = {@link IllegalStateException}/{@link IllegalArgumentException}):
 *       정당하게 입장 턴을 쓴 것으로 보고 <b>토큰을 소진된 채로 둔다</b>.</li>
 *   <li><b>시스템 오류</b>(DB/Redis 장애 등 그 외): 사용자 책임이 아니므로 토큰을
 *       <b>best-effort 재발급</b>해 재진입을 면제한다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderFacade {

    private final AdmissionTokenService admissionTokenService;
    private final OrderService orderService;

    /**
     * 입장 토큰을 원자적으로 소비한 뒤 주문을 생성한다.
     *
     * @throws AdmissionRequiredException 유효한 입장 토큰이 없어 게이트를 통과하지 못한 경우(P-O1)
     */
    public OrderResponse placeOrder(Long memberId, Long goodsId, int quantity) {
        // 1) 입장 토큰을 '원자적으로 소비'한다. DEL 이 true 를 돌려준 단 하나의 요청만 통과한다.
        boolean admitted = admissionTokenService.revoke(goodsId, memberId);
        if (!admitted) {
            throw new AdmissionRequiredException(goodsId, memberId);
        }

        // 2) 게이트 통과분만 주문 도메인으로 흘려보낸다.
        try {
            Order order = orderService.createOrder(memberId, goodsId, quantity);
            return OrderResponse.from(order);
        } catch (RuntimeException e) {
            if (isSystemError(e)) {
                reissueQuietly(goodsId, memberId);
            }
            throw e;
        }
    }

    /** 도메인이 입력/상태/한도 위반에 던지는 예외만 비즈니스 거부로 분류하고, 그 외는 시스템 오류로 본다. */
    private boolean isSystemError(RuntimeException e) {
        return !(e instanceof IllegalArgumentException
            || e instanceof IllegalStateException
            || e instanceof PurchaseLimitExceededException);
    }

    /** 재발급 실패가 원래 오류를 가리지 않도록 삼키고 기록만 한다(Redis 불안정 시 사용자는 재진입으로 복구 가능). */
    private void reissueQuietly(Long goodsId, Long memberId) {
        try {
            admissionTokenService.issue(goodsId, memberId);
        } catch (RuntimeException reissueError) {
            log.warn("입장 토큰 재발급 실패 goodsId={} memberId={}", goodsId, memberId, reissueError);
        }
    }
}
