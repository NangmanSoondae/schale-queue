package com.schale.queue.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화 설정.
 *
 * <p>{@link com.schale.queue.core.domain.BaseTimeEntity} 의
 * {@code @CreatedDate}, {@code @LastModifiedDate} 동작을 위해 필요하다.
 * 설정을 별도 클래스로 분리하여, 테스트 시 Auditing 을 선택적으로 로딩할 수 있게 한다.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
