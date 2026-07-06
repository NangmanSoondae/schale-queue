package com.schale.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 어드민 MCP 서버 전체 컨텍스트 부팅 스모크 테스트(ADR-009).
 *
 * <p><b>왜 필요한가</b>: 웹 계층 없는 실행 모듈은 부팅 결함이 잠복하기 쉽다(troubleshooting No.06 부류).
 * 실제로 이 모듈의 첫 구현에서도 두 건이 실행에서야 드러났다 — 부트스트랩 패키지 위치(JPA 리포지토리
 * 스캔 누락), ObjectMapper 빈 부재(#25 교훈 재현). 이 테스트가 컨텍스트(JPA validate + Redis +
 * MCP 툴 프로바이더)의 정상 로딩을 지켜 회귀를 막는다.
 *
 * <p>JPA(ddl-auto=validate)가 실제 DB 스키마를 검증하므로 {@code RUN_DB_IT=true} 일 때만 실행한다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class AdminMcpContextLoadIT {

    @Test
    @DisplayName("어드민 MCP 컨텍스트가 정상 부팅된다(JPA+Redis+툴 등록)")
    void contextLoads() {
        // 컨텍스트 로딩 성공 자체가 검증이다.
    }
}
