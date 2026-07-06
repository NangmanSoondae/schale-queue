package com.schale.queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI 어드민 MCP 서버 부트스트랩(ADR-009).
 *
 * <p>stdio 전송으로 MCP 클라이언트(Claude Desktop/Code)와 JSON-RPC 통신한다.
 * <b>stdout 은 프로토콜 채널</b>이므로 배너·콘솔 로깅은 application.yml 에서 전부 꺼져 있다 —
 * 어떤 빈도 {@code System.out} 에 쓰면 안 된다(연결이 조용히 깨진다).
 *
 * <p>루트 패키지({@code com.schale.queue})에 두는 이유는 worker 와 동일 — 자동구성 패키지가
 * 여기로 잡혀야 core 의 엔티티/JPA 리포지토리 스캔이 함께 동작한다.
 *
 * <p>core 의 도메인/영속성을 재사용하되 스키마 소유권은 없다(Flyway 비활성, validate 만).
 */
@SpringBootApplication
public class AdminMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminMcpApplication.class, args);
    }
}
