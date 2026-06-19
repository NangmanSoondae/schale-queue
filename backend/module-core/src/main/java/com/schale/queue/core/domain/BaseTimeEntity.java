package com.schale.queue.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 모든 엔티티가 공유하는 생성/수정 시각 추적용 베이스 클래스.
 *
 * <p>Spring Data JPA Auditing 을 통해 {@code createdAt}, {@code updatedAt} 을 자동 관리한다.
 * 활성화를 위해 설정 클래스에 {@code @EnableJpaAuditing} 이 필요하다
 * ({@link com.schale.queue.core.config.JpaAuditingConfig}).
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
