package com.schale.queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Schale Queue Worker 애플리케이션 진입점.
 *
 * <p>사용자 응답 경로와 분리된 백그라운드 처리 모듈이다(docs/2_architecture.md §2.2).
 * 대기열(Redis ZSET)을 정해진 처리량만큼 소비해 입장 토큰을 발급하는 것이 본 모듈의 책임이며,
 * 추후 비동기 알림/정산 워커가 이 컨텍스트 위에서 동작한다.
 *
 * <p>베이스 패키지 {@code com.schale.queue} 하위를 스캔하므로
 * {@code com.schale.queue.core} 의 엔티티/설정/리포지토리/도메인 서비스가 함께 로딩된다.
 *
 * <p><b>주의</b>: 본 슬라이스(Phase 3 ①)에서는 부트스트랩 골격만 둔다.
 * rate 기반 대기열 소비 Consumer 는 다음 슬라이스(③)에서 추가한다.
 */
@SpringBootApplication
public class SchaleQueueWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchaleQueueWorkerApplication.class, args);
    }
}
