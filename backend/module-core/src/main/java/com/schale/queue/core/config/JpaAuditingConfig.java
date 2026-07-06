package com.schale.queue.core.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화 설정.
 *
 * <p>{@link com.schale.queue.core.domain.BaseTimeEntity} 의
 * {@code @CreatedDate}, {@code @LastModifiedDate} 동작을 위해 필요하다.
 * 설정을 별도 클래스로 분리하여, 테스트 시 Auditing 을 선택적으로 로딩할 수 있게 한다.
 *
 * <p><b>시간 출처 통일(리뷰 '시간대 3원화')</b>: 기본 DateTimeProvider 는 시스템 기본 존을 쓰므로
 * 도메인(UTC {@link Clock})과 같은 행에서 9시간 어긋난 감사 시각이 저장됐다(troubleshooting No.10 부류).
 * 주입된 Clock(UTC, {@link TimeConfig}) 기반 provider 로 도메인·감사·이벤트 시각을 한 출처로 맞춘다.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(LocalDateTime.now(clock));
    }
}
