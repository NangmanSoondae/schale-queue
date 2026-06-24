package com.schale.queue.core.domain.queue;

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

    /**
     * 입장 토큰 전역 TTL(P-Q3). "큐 통과 ~ 주문 생성"까지의 입장 권한 수명.
     * 만료 시 주문 불가, 재진입 필요. 기본 5분.
     */
    private Duration admissionTtl = Duration.ofMinutes(5);
}
