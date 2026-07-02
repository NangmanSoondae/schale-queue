package com.schale.queue.core.domain;

/**
 * 요청한 도메인 자원(상품/재고/결제/주문)이 존재하지 않음. API 계층에서 <b>404</b> 로 매핑된다.
 *
 * <p>과거엔 {@code IllegalArgumentException} 을 재사용해 "자원 없음"이 400(클라이언트 입력 오류)으로
 * 나갔다(2026-07-02 리뷰 M3/M4). 모니터링에서 입력 실수와 자원 부재를 구분하고, REST 시맨틱을
 * 바로잡기 위해 전용 예외로 분리한다. 소유권 불일치(타인 자원 접근)도 존재 여부를 숨기기 위해
 * 이 예외로 응답한다(순차 ID 열거 방어 — PaymentService.confirm).
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
