package com.schale.queue.core;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;

/**
 * module-core 통합 테스트 전용 부트 애플리케이션.
 *
 * <p>module-core 는 라이브러리라 자체 진입점이 없으므로, {@code @SpringBootTest} 가
 * 사용할 설정 클래스를 테스트 소스에 둔다. 본 테스트 범위는 JPA/영속성이므로
 * Redis 자동 구성은 제외하여 불필요한 연결 시도를 막는다.
 */
@SpringBootApplication(exclude = {
    RedisAutoConfiguration.class,
    RedisRepositoriesAutoConfiguration.class
})
public class CoreTestApplication {
}
