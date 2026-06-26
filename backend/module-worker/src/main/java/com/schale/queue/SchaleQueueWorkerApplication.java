package com.schale.queue;

import com.schale.queue.core.config.JpaAuditingConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Schale Queue Worker 애플리케이션 진입점.
 *
 * <p>사용자 응답 경로와 분리된 백그라운드 처리 모듈이다(docs/2_architecture.md §2.2).
 * 대기열(Redis ZSET)을 정해진 처리량만큼 소비해 입장 토큰을 발급하는 것이 본 모듈의 책임이며
 * ({@link com.schale.queue.worker.queue.QueueConsumer}), 추후 비동기 알림/정산 워커가 이 컨텍스트 위에서 동작한다.
 *
 * <p><b>왜 {@code @SpringBootApplication} 을 풀어 썼나(부팅 설계)</b>:
 * 본 워커는 <b>Redis 만</b> 사용하고 DB 는 건드리지 않는다. 따라서
 * <ul>
 *   <li>DataSource/JPA 자동 구성을 <b>제외</b>해 MariaDB 없이도 기동한다(부팅 가속·장애 격리,
 *       ADR-002 §3.3). DB 를 쓰는 워커(주문/정산)가 생기면 그 슬라이스에서 되살린다.</li>
 *   <li>의존 모듈 module-core 를 컴포넌트 스캔하되, JPA 전제 빈인 {@link JpaAuditingConfig}
 *       (= {@code @EnableJpaAuditing}, JPA 컨텍스트 필요) 만 스캔에서 제외한다. 이를 위해
 *       {@code @SpringBootApplication} 을 구성 3요소로 분해했다(스캔 필터 커스터마이즈의 정석).</li>
 * </ul>
 * 웹 계층은 두지 않으며({@code spring.main.web-application-type=none}), 소비 스케줄러
 * ({@code @EnableScheduling}) 의 비데몬 스레드가 프로세스를 살아 있게 유지한다.
 *
 * <p>베이스 패키지 {@code com.schale.queue} 하위를 스캔하므로 {@code com.schale.queue.core} 의
 * 대기열 도메인 서비스(QueueService/AdmissionTokenService)·RedisConfig 와, QueueService 가
 * 주입받는 {@code Clock} 을 제공하는 TimeConfig 가 함께 로딩된다.
 */
@Configuration
@EnableAutoConfiguration(exclude = {
    DataSourceAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class,
    JpaRepositoriesAutoConfiguration.class
})
@ComponentScan(
    basePackages = "com.schale.queue",
    excludeFilters = {
        @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JpaAuditingConfig.class),
        // 현 워커는 Redis 전용(대기열 소비)이라 JPA 를 끈다(ADR-002 §3.3). 그런데 core 의 JPA 결합
        // 도메인 서비스(OrderService→StockService→StockRepository 등)까지 스캔하면 JPA 리포지토리
        // 빈이 없어 컨텍스트가 깨진다. 주문/재고/결제/상품/회원 도메인을 스캔에서 제외한다.
        // (DB 를 쓰는 워커 — 결제 만료 release 등 — 가 생기면 그 슬라이스에서 JPA 와 함께 되살린다.)
        @Filter(type = FilterType.REGEX,
            pattern = "com\\.schale\\.queue\\.core\\.domain\\.(order|stock|payment|goods|member)\\..*")
    })
@EnableScheduling
public class SchaleQueueWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchaleQueueWorkerApplication.class, args);
    }
}
