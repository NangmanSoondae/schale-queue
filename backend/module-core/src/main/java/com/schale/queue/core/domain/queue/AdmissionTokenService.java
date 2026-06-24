package com.schale.queue.core.domain.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 입장 토큰 도메인 서비스(P-Q3).
 *
 * <p>대기열을 통과한 회원에게 "주문을 생성할 수 있는 입장 권한"을 발급한다.
 * 토큰은 <b>Redis 키 + TTL</b> 로 표현하며, 키 자체의 존재가 곧 유효성이다.
 * TTL 이 만료되면 키가 사라져 주문이 불가해지고 재진입이 필요하다.
 *
 * <p>발급은 <b>최초 1회만 TTL 을 고정</b>한다({@code SET NX}). 반복 호출로 TTL 이 슬라이딩되어
 * "큐 통과 ~ 주문 생성" 수명이 무한정 연장되는 것을 막기 위함이다(P-Q3 고정창).
 * 재입장으로 <b>새 수명</b>이 필요하면 {@link #revoke} 후 다시 {@link #issue} 한다.
 *
 * <p>입장 토큰은 <b>회원 인증과 별개</b>로 "입장 권한"만 증명한다. TTL 수명은
 * "큐 통과 ~ 주문 생성"까지이며, "주문 생성 ~ 결제 완료"의 재고 hold 수명
 * ({@code Payment.timeoutAt}, P-O2)과는 별개 타이머다.
 */
@Service
@RequiredArgsConstructor
public class AdmissionTokenService {

    private final StringRedisTemplate redis;
    private final QueueProperties properties;

    /**
     * 입장 토큰을 발급한다(P-Q3). 키 {@code admission:{goodsId}:{memberId}} 에
     * 전역 TTL({@link QueueProperties#getAdmissionTtl()})을 <b>최초 1회만</b> 건다({@code SET NX}).
     * 이미 유효한 토큰이 있으면 TTL 을 건드리지 않아 고정창을 보존한다.
     *
     * @return 새로 발급되었으면 {@code true}, 이미 유효한 토큰이 있어 발급을 건너뛰면 {@code false}
     */
    public boolean issue(Long goodsId, Long memberId) {
        Boolean set = redis.opsForValue().setIfAbsent(
            QueueKeys.admission(goodsId, memberId),
            "1",
            properties.getAdmissionTtl()
        );
        return Boolean.TRUE.equals(set);
    }

    /**
     * 유효한 입장 토큰 보유 여부(P-O1 주문 전제). 키가 살아 있으면 {@code true}.
     */
    public boolean hasValidToken(Long goodsId, Long memberId) {
        return Boolean.TRUE.equals(redis.hasKey(QueueKeys.admission(goodsId, memberId)));
    }

    /**
     * 입장 토큰을 회수한다(주문 생성으로 권한을 소진했을 때 등).
     *
     * @return 실제로 키가 존재해 삭제되었으면 {@code true}
     */
    public boolean revoke(Long goodsId, Long memberId) {
        return Boolean.TRUE.equals(redis.delete(QueueKeys.admission(goodsId, memberId)));
    }
}
