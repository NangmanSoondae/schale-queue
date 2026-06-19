package com.schale.queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Schale Queue API 애플리케이션 진입점.
 *
 * <p>베이스 패키지 {@code com.schale.queue} 하위를 스캔하므로
 * {@code com.schale.queue.core} 의 엔티티/설정/리포지토리가 함께 로딩된다.
 */
@SpringBootApplication
public class SchaleQueueApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchaleQueueApplication.class, args);
    }
}
