package com.schale.queue.core.domain.queue;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 대기열 운영 파라미터(외부화 설정, §5.4.1-4 하드코딩 금지).
 *
 * <p>{@code schale.queue.*} 프리픽스로 환경변수/yml 에서 재정의할 수 있다.
 * 기본값은 도메인 정책(docs/11_domain_policy.md)을 코드 기본값으로 옮긴 것이다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "schale.queue")
public class QueueProperties {

    /** Redis ZSET score(double)가 정수를 정확히 표현할 수 있는 상한 = 2^53. */
    private static final double DOUBLE_EXACT_INT_LIMIT = 9_007_199_254_740_992.0d;

    /** 정밀도 검증 기준 — 충분히 먼 미래(서기 ~2128, epoch ms ≈ 5.0e12)까지 안전을 보장한다. */
    private static final double FAR_FUTURE_MILLIS = 5_000_000_000_000.0d;

    /**
     * 입장 토큰 전역 TTL(P-Q3). "큐 통과 ~ 주문 생성"까지의 입장 권한 수명.
     * 만료 시 주문 불가, 재진입 필요. 기본 5분.
     */
    private Duration admissionTtl = Duration.ofMinutes(5);

    /**
     * 대기열 score 의 <b>동점 해소(tie-break) 자릿수</b>(P-Q1).
     *
     * <p>score = {@code millis × 10^d + (진입 시퀀스 mod 10^d)} 에서의 {@code d}.
     * 같은 밀리초에 진입한 회원들을 <b>도착 순서</b>로 구분하는 하위 자릿수다
     * (d=3 → 같은 ms 안 최대 1000명까지 충돌 없이 구분).
     *
     * <p>⚠️ Redis ZSET score 는 double(정수 정확표현 한계 {@code 2^53})이라
     * {@code millis × 10^d} 가 한계를 넘으면 하위 자릿수가 반올림으로 소실된다.
     * 따라서 상한이 있으며({@code d ≤ 3}) {@link #validate()} 에서 강제한다.
     * 더 큰 엔트로피가 필요하면 score 모델 자체를 바꿔야 한다(순수 시퀀스 등).
     */
    private int scoreTiebreakDigits = 3;

    /** tie-break 배율 {@code 10^scoreTiebreakDigits}. */
    public long scoreScale() {
        long scale = 1L;
        for (int i = 0; i < scoreTiebreakDigits; i++) {
            scale *= 10L;
        }
        return scale;
    }

    /**
     * 설정값 정합성 검증. Spring 이 프로퍼티 바인딩을 마친 뒤 호출되어,
     * double 정밀도를 깨는 값으로 <b>조용히 잘못 동작</b>하기 전에 부팅을 실패시킨다.
     */
    @PostConstruct
    void validate() {
        if (scoreTiebreakDigits < 1) {
            throw new IllegalStateException(
                "schale.queue.score-tiebreak-digits 는 1 이상이어야 한다: " + scoreTiebreakDigits);
        }
        if (FAR_FUTURE_MILLIS * scoreScale() >= DOUBLE_EXACT_INT_LIMIT) {
            throw new IllegalStateException(
                "schale.queue.score-tiebreak-digits=" + scoreTiebreakDigits
                + " 는 Redis score double 정밀도(2^53)를 초과한다. 최대 3 까지 허용된다.");
        }
    }
}
