package com.schale.queue.core;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * module-core 통합 테스트 전용 부트 애플리케이션.
 *
 * <p>module-core 는 라이브러리라 자체 진입점이 없으므로, {@code @SpringBootTest} 가
 * 사용할 설정 클래스를 테스트 소스에 둔다.
 *
 * <p>대기열 도메인 서비스(QueueService/AdmissionTokenService)가 {@code StringRedisTemplate}
 * 에 의존하므로 Redis 자동 구성을 더 이상 제외하지 않는다. Lettuce 커넥션은 <b>지연 생성</b>되어
 * 실제 명령 시점에만 연결되므로, JPA 전용 테스트(재고/주문)는 Redis 미사용으로 연결을 시도하지 않는다.
 */
@SpringBootApplication
public class CoreTestApplication {
}
