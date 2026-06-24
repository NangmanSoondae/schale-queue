package com.schale.queue.core.config;

import com.schale.queue.core.domain.queue.QueueProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 대기열 Redis 인프라 설정.
 *
 * <p>대기열은 <b>String 멤버 + double score(timestamp)</b> 의 ZSET 이고, 입장 토큰은
 * <b>String 값 + TTL</b> 이므로 Spring Boot 가 자동 구성하는 {@code StringRedisTemplate}
 * (키/값 {@code StringRedisSerializer})으로 충분하다. 별도의 직렬화/커넥션 커스터마이즈가
 * 필요해지면 이 클래스에서 빈을 확장한다.
 *
 * <p>여기서는 대기열 운영 파라미터({@link QueueProperties})를 활성화하는 책임만 가진다.
 * 커넥션을 요구하는 빈을 두지 않으므로, Redis 미기동 환경(예: JPA 전용 통합 테스트)에서도
 * 컨텍스트 로딩을 막지 않는다.
 */
@Configuration
@EnableConfigurationProperties(QueueProperties.class)
public class RedisConfig {
}
