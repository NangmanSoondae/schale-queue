package com.schale.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 워커 전체 컨텍스트 부팅 스모크 테스트.
 *
 * <p><b>왜 필요한가</b>: 워커는 한때 단위 테스트만 있고 전체 컨텍스트 부팅을 한 번도 안 거쳐 부팅 결함이
 * 잠복했었다(JPA 결합 빈 스캔·VT 데몬 스레드 즉시 종료, troubleshooting No.06). 이 테스트가 컨텍스트
 * (JPA + Redis + 스케줄러 + QueueConsumer + PaymentExpiryWorker)가 정상 로딩되는지를 지켜 회귀를 막는다.
 *
 * <p>JPA(ddl-auto=validate)가 부팅 시 실제 DB 스키마를 검증하므로 MariaDB 가 필요하다 →
 * {@code RUN_DB_IT=true} 일 때만 실행한다(일반 빌드·CI 는 skip).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class WorkerContextLoadIT {

    @Test
    @DisplayName("워커 컨텍스트가 정상 부팅된다(JPA+Redis+스케줄러)")
    void contextLoads() {
        // 컨텍스트 로딩 성공 자체가 검증이다.
    }
}
