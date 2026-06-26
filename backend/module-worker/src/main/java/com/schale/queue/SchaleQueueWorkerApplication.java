package com.schale.queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Schale Queue Worker 애플리케이션 진입점.
 *
 * <p>사용자 응답 경로와 분리된 백그라운드 처리 모듈이다(docs/2_architecture.md §2.2). 두 가지 스케줄 작업을 담당한다:
 * <ul>
 *   <li><b>대기열 소비</b>({@link com.schale.queue.worker.queue.QueueConsumer}) — Redis ZSET 을 정해진
 *       처리량만큼 꺼내 입장 토큰을 발급(P-Q4).</li>
 *   <li><b>결제 만료 정리</b>({@link com.schale.queue.worker.payment.PaymentExpiryWorker}) — {@code timeoutAt}
 *       경과 결제를 EXPIRED 로 정리하고 예약 재고를 해제(UC-07/P-S4). <b>DB(JPA)가 필요</b>하다.</li>
 * </ul>
 *
 * <p><b>JPA 재활성화(2026-06-26)</b>: 본래 워커는 Redis 전용으로 DataSource/JPA 자동구성을 제외했으나
 * (ADR-002 §3.3 부팅 가속·장애 격리), 만료 정리(UC-07)가 DB 를 쓰므로 예고대로 JPA 를 되살렸다.
 * 따라서 일반 {@code @SpringBootApplication}(전체 스캔 + JPA/DataSource 자동구성)으로 복귀한다.
 * 웹 계층은 두지 않고({@code spring.main.web-application-type=none}), Virtual Threads 활성 시 스케줄러가
 * 데몬 스레드라 {@code spring.main.keep-alive=true} 로 프로세스를 살아 있게 유지한다(troubleshooting No.06).
 */
@SpringBootApplication
@EnableScheduling
public class SchaleQueueWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchaleQueueWorkerApplication.class, args);
    }
}
