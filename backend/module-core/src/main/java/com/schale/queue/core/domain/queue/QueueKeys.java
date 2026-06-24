package com.schale.queue.core.domain.queue;

/**
 * 대기열 Redis 키 네이밍 규약(단일 출처).
 *
 * <p>키 문자열을 코드 곳곳에 흩뿌리면 오타·불일치로 키가 갈라져 대기열이 깨진다.
 * 모든 대기열 키는 반드시 이 클래스를 통해 생성한다.
 *
 * <ul>
 *   <li><b>대기열(ZSET)</b> {@code queue:{goodsId}} — score=진입 timestamp, member=memberId (P-Q1 FIFO)</li>
 *   <li><b>입장 토큰(String+TTL)</b> {@code admission:{goodsId}:{memberId}} — 전역 TTL(P-Q3)</li>
 * </ul>
 */
public final class QueueKeys {

    private QueueKeys() {
    }

    /** 상품별 대기열 ZSET 키. score=진입 timestamp, member=memberId. */
    public static String waitingQueue(Long goodsId) {
        return "queue:" + goodsId;
    }

    /** 입장 토큰 키(P-Q3 규약 {@code admission:{goodsId}:{memberId}}). */
    public static String admission(Long goodsId, Long memberId) {
        return "admission:" + goodsId + ":" + memberId;
    }
}
