package com.schale.queue.core.domain.queue;

/**
 * 대기열 Redis 키 네이밍 규약(단일 출처).
 *
 * <p>키 문자열을 코드 곳곳에 흩뿌리면 오타·불일치로 키가 갈라져 대기열이 깨진다.
 * 모든 대기열 키는 반드시 이 클래스를 통해 생성한다.
 *
 * <ul>
 *   <li><b>대기열(ZSET)</b> {@code queue:{goodsId}} — score=진입 시각+시퀀스, member=memberId (P-Q1 FIFO)</li>
 *   <li><b>진입 시퀀스(String 카운터)</b> {@code queue:{goodsId}:seq} — 같은 ms 동점 해소용 단조 증가</li>
 *   <li><b>활성 큐 레지스트리(Set)</b> {@code queue:active} — 대기자가 있는 goodsId 집합 (Worker 소비 대상 발견)</li>
 *   <li><b>입장 토큰(String+TTL)</b> {@code admission:{goodsId}:{memberId}} — 전역 TTL(P-Q3)</li>
 * </ul>
 */
public final class QueueKeys {

    private QueueKeys() {
    }

    /** 상품별 대기열 ZSET 키. score=진입 시각+시퀀스, member=memberId. */
    public static String waitingQueue(Long goodsId) {
        return "queue:" + goodsId;
    }

    /** 상품별 진입 시퀀스 키. 같은 밀리초 진입자를 도착 순서로 구분하는 단조 증가 카운터(P-Q1). */
    public static String sequence(Long goodsId) {
        return "queue:" + goodsId + ":seq";
    }

    /**
     * 활성 큐 레지스트리 Set 키. 대기자가 있는 goodsId 집합을 담아 Worker 가 소비 대상을 발견한다.
     * enqueue 시 {@code SADD}, 큐가 비면 dequeue 시 {@code SREM} 으로 정리한다.
     * (goodsId 는 항상 숫자라 {@code queue:{goodsId}} 키와 절대 충돌하지 않는다.)
     */
    public static String activeGoods() {
        return "queue:active";
    }

    /** 입장 토큰 키(P-Q3 규약 {@code admission:{goodsId}:{memberId}}). */
    public static String admission(Long goodsId, Long memberId) {
        return "admission:" + goodsId + ":" + memberId;
    }
}
