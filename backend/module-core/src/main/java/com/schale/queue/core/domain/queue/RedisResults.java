package com.schale.queue.core.domain.queue;

/**
 * Redis 명령 결과({@code Boolean}) 해석 헬퍼.
 *
 * <p>{@code RedisTemplate} 류는 키 부재·파이프라인/트랜잭션 모드 등에서 {@code null} 을 반환할 수 있어
 * "성공/존재"를 판단할 때 NPE 안전한 {@code Boolean.TRUE.equals(...)} 가 반복된다. 그 의도를
 * 한 곳에서 이름으로 드러내 가독성과 일관성을 확보한다. 큐 도메인 내부 표현이므로 package-private 으로 둔다.
 */
final class RedisResults {

    private RedisResults() {
    }

    /** {@code null}(미정의) 과 {@code false} 를 모두 "아님"으로 보고, {@code TRUE} 일 때만 참. */
    static boolean isTrue(Boolean result) {
        return Boolean.TRUE.equals(result);
    }
}
