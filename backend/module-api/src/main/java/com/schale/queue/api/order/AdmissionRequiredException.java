package com.schale.queue.api.order;

/**
 * 유효한 입장 토큰 없이 주문을 시도했을 때 던지는 예외(P-O1 위반).
 *
 * <p>토큰 만료/미보유/이미 소비됨을 구분하지 않고 "입장 권한 없음"으로 통합한다.
 * 전역 예외 핸들러가 이를 {@code 403 Forbidden} 으로 매핑한다.
 */
public class AdmissionRequiredException extends RuntimeException {

    public AdmissionRequiredException(Long goodsId, Long memberId) {
        super("유효한 입장 토큰이 없습니다. 대기열 입장 후 다시 시도하세요. goodsId=" + goodsId + ", memberId=" + memberId);
    }
}
