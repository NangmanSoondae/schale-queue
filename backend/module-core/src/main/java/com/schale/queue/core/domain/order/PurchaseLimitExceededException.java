package com.schale.queue.core.domain.order;

/**
 * 1인 구매 한도(P-O3) 위반 시 던지는 예외.
 *
 * <p>두 경우를 포괄한다: ① 주문 수량이 {@code Goods.maxPurchasePerMember} 를 초과,
 * ② 같은 회원이 같은 상품의 활성 주문을 이미 보유(슬롯 유니크 제약 충돌).
 */
public class PurchaseLimitExceededException extends RuntimeException {

    public PurchaseLimitExceededException(String message) {
        super(message);
    }
}
