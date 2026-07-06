package com.schale.queue.core.domain.queue;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 입장 토큰 도메인 서비스(P-Q3).
 *
 * <p>대기열을 통과한 회원의 "주문을 생성할 수 있는 입장 권한"을 검증·소진한다.
 * 토큰은 <b>Redis 키 + TTL</b> 로 표현하며, 키 자체의 존재가 곧 유효성이다.
 * TTL 이 만료되면 키가 사라져 주문이 불가해지고 재진입이 필요하다.
 *
 * <p><b>발급은 이 서비스가 아니라 {@link QueueService#dequeueAndAdmit} 의 Lua 스크립트가 수행</b>한다
 * — pop 과 발급 사이의 크래시로 회원이 유실되지 않도록 하나의 원자 연산으로 묶었기 때문이다
 * (2026-07-02 리뷰 H2). 발급 시맨틱(P-Q3 고정창: {@code SET NX} 최초 1회만 TTL, 재진입자의 기존
 * TTL 보존)은 그 스크립트가 그대로 유지한다.
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
     * 입장 토큰을 발급한다({@code SET NX} — 이미 유효한 토큰이 있으면 TTL 을 건드리지 않는다).
     *
     * <p><b>대기열 통과의 주 발급 경로가 아니다</b>(그건 {@link QueueService#dequeueAndAdmit} 의
     * Lua 스크립트). 이 메서드는 <b>실패 보상 재발급</b> 전용이다: 게이트({@code OrderFacade})가
     * 토큰을 소비(revoke)한 뒤 시스템 오류로 주문이 실패하면, 사용자 책임이 아니므로 재진입을
     * 면제하기 위해 best-effort 로 다시 발급한다(새 고정창 TTL 시작).
     *
     * @return 새로 발급되었으면 {@code true}, 이미 유효한 토큰이 있어 건너뛰면 {@code false}
     */
    public boolean issue(Long goodsId, Long memberId) {
        Boolean set = redis.opsForValue().setIfAbsent(
            QueueKeys.admission(goodsId, memberId),
            "1",
            properties.getAdmissionTtl()
        );
        return RedisResults.isTrue(set);
    }

    /**
     * 유효한 입장 토큰 보유 여부(P-O1 주문 전제). 키가 살아 있으면 {@code true}.
     */
    public boolean hasValidToken(Long goodsId, Long memberId) {
        return RedisResults.isTrue(redis.hasKey(QueueKeys.admission(goodsId, memberId)));
    }

    /**
     * 여러 회원의 유효 토큰 보유 여부를 <b>{@code MGET} 1회 왕복</b>으로 일괄 확인한다.
     *
     * <p>SSE 브로드캐스트의 벌크 경로(리뷰 M8) — 구독자별 {@code EXISTS} 개별 왕복을 제거한다.
     *
     * @return 유효한 입장 토큰을 보유한 memberId 집합
     */
    public Set<Long> membersWithValidToken(Long goodsId, Collection<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ordered = List.copyOf(memberIds);
        List<String> values = redis.opsForValue().multiGet(
            ordered.stream().map(memberId -> QueueKeys.admission(goodsId, memberId)).toList());
        Set<Long> admitted = new HashSet<>();
        if (values == null) {
            return admitted;
        }
        for (int i = 0; i < ordered.size(); i++) {
            if (values.get(i) != null) {
                admitted.add(ordered.get(i));
            }
        }
        return admitted;
    }

    /**
     * 입장 토큰을 회수한다(주문 생성으로 권한을 소진했을 때 등).
     *
     * @return 실제로 키가 존재해 삭제되었으면 {@code true}
     */
    public boolean revoke(Long goodsId, Long memberId) {
        return RedisResults.isTrue(redis.delete(QueueKeys.admission(goodsId, memberId)));
    }
}
