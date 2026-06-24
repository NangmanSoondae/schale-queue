package com.schale.queue.core.domain.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 대기열 도메인 정합성 통합 테스트(Redis ZSET / 입장 토큰).
 *
 * <p><b>증명 목표</b>: 정책 P-Q1(FIFO 공정성)·P-Q2(중복 진입 멱등)·P-Q3(입장 토큰 TTL)·
 * 동시 진입 시 순번 유일성이 실제 Redis 위에서 성립함을 검증한다.
 *
 * <p>Testcontainers 와 로컬 Docker Engine 의 API 비호환(troubleshooting No.03) 때문에,
 * 재고 동시성 테스트와 동일하게 {@code docker-compose} 로 띄운 실제 Redis 에 직접 접속한다.
 * 단, 대기열 검증은 JPA/DB 가 전혀 필요 없으므로 전체 Spring 컨텍스트를 띄우지 않고
 * {@link LettuceConnectionFactory} 만 직접 구성해 <b>DB 기동에 의존하지 않게</b> 한다.
 * 인프라가 필요한 통합 테스트이므로 {@code RUN_REDIS_IT=true} 일 때만 실행된다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_IT", matches = "true")
class QueueIntegrationTest {

    private static final Long GOODS = 9001L;

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        String host = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        factory = new LettuceConnectionFactory(host, port);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @BeforeEach
    @AfterEach
    void cleanup() {
        redis.delete(QueueKeys.waitingQueue(GOODS));
        redis.delete(QueueKeys.sequence(GOODS));
        Set<String> admissionKeys = redis.keys("admission:" + GOODS + ":*");
        if (admissionKeys != null && !admissionKeys.isEmpty()) {
            redis.delete(admissionKeys);
        }
    }

    // --- 대기열 (QueueService) ---------------------------------------------

    @Test
    @DisplayName("P-Q1: 진입 timestamp 오름차순으로 선착순(FIFO) 순번이 매겨진다")
    void enqueue_assigns_fifo_positions_by_timestamp() {
        // given — 진입 시각이 1초씩 늘어나는 조정 가능한 시계
        AdjustableClock clock = new AdjustableClock(Instant.parse("2026-06-24T00:00:00Z"));
        QueueService queue = new QueueService(redis, clock, new QueueProperties());

        // when — 1 → 2 → 3 순서로 진입 (각 진입마다 시각 전진)
        assertThat(queue.enqueue(GOODS, 1L)).isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(queue.enqueue(GOODS, 2L)).isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(queue.enqueue(GOODS, 3L)).isTrue();

        // then — 진입 순서대로 1, 2, 3번
        assertThat(queue.getPosition(GOODS, 1L)).hasValue(1L);
        assertThat(queue.getPosition(GOODS, 2L)).hasValue(2L);
        assertThat(queue.getPosition(GOODS, 3L)).hasValue(3L);
        assertThat(queue.size(GOODS)).isEqualTo(3L);
        assertThat(queue.getPosition(GOODS, 999L)).isEmpty();
    }

    @Test
    @DisplayName("P-Q2: 이미 대기 중인 회원의 재진입은 무시되고 기존 순번을 유지한다")
    void duplicate_enqueue_keeps_original_position() {
        // given — 1번이 먼저, 2번이 나중에 진입
        AdjustableClock clock = new AdjustableClock(Instant.parse("2026-06-24T00:00:00Z"));
        QueueService queue = new QueueService(redis, clock, new QueueProperties());
        queue.enqueue(GOODS, 1L);
        clock.advance(Duration.ofSeconds(1));
        queue.enqueue(GOODS, 2L);

        // when — 한참 뒤 1번이 다시 진입 시도
        clock.advance(Duration.ofMinutes(5));
        boolean reAdded = queue.enqueue(GOODS, 1L);

        // then — 신규 발급되지 않고(false), 1번은 여전히 맨 앞(뒤로 밀리지 않음)
        assertThat(reAdded).as("이미 대기 중이면 재진입은 무시되어야 한다").isFalse();
        assertThat(queue.getPosition(GOODS, 1L)).hasValue(1L);
        assertThat(queue.getPosition(GOODS, 2L)).hasValue(2L);
        assertThat(queue.size(GOODS)).isEqualTo(2L);
    }

    @Test
    @DisplayName("P-Q1: 같은 밀리초 진입은 도착 순서(시퀀스)로 구분되며 memberId 사전순에 좌우되지 않는다")
    void same_millisecond_enqueue_orders_by_arrival_not_lexicographic() {
        // given — 시각을 고정(전진하지 않음) → 두 진입의 millis 가 동일
        AdjustableClock frozen = new AdjustableClock(Instant.parse("2026-06-24T00:00:00Z"));
        QueueService queue = new QueueService(redis, frozen, new QueueProperties());

        // when — 9 가 먼저, 1000 이 나중에 진입 (같은 ms)
        //   member 문자열 사전순이라면 "1000" < "9" 라 1000 이 앞서야 하지만,
        //   시퀀스 동점 해소가 도착 순서를 보존해야 한다.
        assertThat(queue.enqueue(GOODS, 9L)).isTrue();
        assertThat(queue.enqueue(GOODS, 1000L)).isTrue();

        // then — 도착 순서대로 9 가 1번, 1000 이 2번 (사전순이었다면 반대였을 것)
        assertThat(queue.getPosition(GOODS, 9L))
            .as("먼저 도착한 9 가 1번이어야 한다 (사전순 편향 없음)").hasValue(1L);
        assertThat(queue.getPosition(GOODS, 1000L))
            .as("나중 도착한 1000 이 2번이어야 한다").hasValue(2L);
    }

    @Test
    @DisplayName("동시 진입 100건에도 순번은 1..N 으로 빠짐없이 유일하게 배정된다")
    void concurrent_enqueue_assigns_unique_positions() throws InterruptedException {
        // given — 실제 시스템 시계 기반 서비스, 100명이 동시에 진입
        QueueService queue = new QueueService(redis, Clock.systemUTC(), new QueueProperties());
        int memberCount = 100;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(memberCount);

        // when — 100개 가상 스레드가 동시에 서로 다른 회원으로 진입
        for (int i = 1; i <= memberCount; i++) {
            long memberId = i;
            executor.submit(() -> {
                try {
                    queue.enqueue(GOODS, memberId);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.shutdown();

        // then — 정확히 100명, 순번은 1..100 의 빠짐없는 집합(유일성 보장)
        assertThat(queue.size(GOODS)).isEqualTo(memberCount);
        Set<Long> positions = ConcurrentHashMap.newKeySet();
        for (int i = 1; i <= memberCount; i++) {
            queue.getPosition(GOODS, (long) i).ifPresent(positions::add);
        }
        assertThat(positions).hasSize(memberCount);
        assertThat(positions).containsExactlyInAnyOrderElementsOf(
            java.util.stream.LongStream.rangeClosed(1, memberCount).boxed().toList());
    }

    // --- 입장 토큰 (AdmissionTokenService) ----------------------------------

    @Test
    @DisplayName("P-Q3/P-O1: 입장 토큰 발급 후 유효하며, 회수하면 무효가 된다")
    void admission_token_issue_validate_revoke() {
        // given — TTL 5분짜리 토큰 서비스
        QueueProperties props = new QueueProperties();
        props.setAdmissionTtl(Duration.ofMinutes(5));
        AdmissionTokenService tokens = new AdmissionTokenService(redis, props);

        // when / then — 발급 전엔 무효, 발급 후 유효, 회수 후 다시 무효
        assertThat(tokens.hasValidToken(GOODS, 1L)).isFalse();
        tokens.issue(GOODS, 1L);
        assertThat(tokens.hasValidToken(GOODS, 1L)).isTrue();
        assertThat(tokens.revoke(GOODS, 1L)).isTrue();
        assertThat(tokens.hasValidToken(GOODS, 1L)).isFalse();
        assertThat(tokens.revoke(GOODS, 1L)).as("이미 없는 토큰 회수는 false").isFalse();
    }

    @Test
    @DisplayName("P-Q3: 입장 토큰은 TTL 이 지나면 자동으로 만료된다")
    void admission_token_expires_after_ttl() {
        // given — TTL 1초 짜리 토큰
        QueueProperties props = new QueueProperties();
        props.setAdmissionTtl(Duration.ofSeconds(1));
        AdmissionTokenService tokens = new AdmissionTokenService(redis, props);

        // when — 발급 직후엔 유효 (1초 창이라 단언 직전 만료될 여지가 없음)
        tokens.issue(GOODS, 1L);
        assertThat(tokens.hasValidToken(GOODS, 1L)).isTrue();

        // then — TTL 경과 후 자동 만료. 고정 sleep 대신 폴링으로 대기해
        //   CI 의 일시정지(GC 등)에도 강건하게 "최대 3초 안에 만료"를 검증한다.
        await().atMost(Duration.ofSeconds(3))
            .pollInterval(Duration.ofMillis(100))
            .untilAsserted(() -> assertThat(tokens.hasValidToken(GOODS, 1L))
                .as("TTL 경과 후 입장 토큰은 사라져 재진입이 필요하다")
                .isFalse());
    }

    /**
     * 진입 시각을 결정적으로 조정하기 위한 테스트용 시계.
     * FIFO 순번이 시각 순서에 따라 매겨지는지 검증할 때 사용한다.
     */
    private static final class AdjustableClock extends Clock {
        private Instant instant;

        private AdjustableClock(Instant start) {
            this.instant = start;
        }

        private void advance(Duration delta) {
            this.instant = this.instant.plus(delta);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
