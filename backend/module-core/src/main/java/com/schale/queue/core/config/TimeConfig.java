package com.schale.queue.core.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 도메인 시간 기준 설정.
 *
 * <p>대기열 진입 timestamp(P-Q1) 등 시각 의존 로직이 주입받아 사용하며,
 * 테스트에서 조정 가능한 {@link Clock} 으로 교체해 결정성을 확보한다.
 *
 * <p>Redis 등 특정 인프라에 종속되지 않는 횡단 관심사이므로 인프라 설정과 분리해 둔다.
 */
@Configuration
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
