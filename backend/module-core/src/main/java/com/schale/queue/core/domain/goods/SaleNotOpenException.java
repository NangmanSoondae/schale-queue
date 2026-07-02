package com.schale.queue.core.domain.goods;

/**
 * 판매 시작({@code Goods.openAt}) 이전의 진입/주문 시도(UC-02 예외 흐름).
 * API 계층에서 <b>409 SALE_NOT_OPEN</b> 으로 매핑된다.
 *
 * <p>선착순 판매의 "출발선"을 지키는 게이트다: openAt 이전엔 대기열 진입도, (입장 토큰이 있어도)
 * 주문 생성도 거부한다. 스펙엔 확정 정책이었으나 미구현 상태였다(2026-07-02 리뷰 M5).
 */
public class SaleNotOpenException extends RuntimeException {

    public SaleNotOpenException(String message) {
        super(message);
    }
}
