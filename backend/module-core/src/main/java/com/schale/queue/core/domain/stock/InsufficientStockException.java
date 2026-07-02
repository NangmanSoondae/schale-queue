package com.schale.queue.core.domain.stock;

/**
 * 가용 재고 부족(P-S1 오버셀 차단). 정상적인 품절 상황의 <b>비즈니스 거부</b> 신호로,
 * API 계층에서 <b>409 STOCK_CONFLICT</b> 로 매핑되고 입장 게이트는 토큰을 소진된 채로 둔다.
 *
 * <p>과거엔 {@code IllegalStateException} 을 재사용했는데, 시스템 오류(아웃박스 직렬화 실패 등)도
 * 같은 타입을 써서 409 로 오분류되고 입장 토큰이 부당하게 소각됐다(2026-07-02 리뷰 M3).
 * 품절만 이 전용 예외로 던지고, 내부 불변식 위반(예약 카운터 부족 등)은 계속
 * {@code IllegalStateException}(= 500 시스템 오류)으로 남긴다.
 */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String message) {
        super(message);
    }
}
