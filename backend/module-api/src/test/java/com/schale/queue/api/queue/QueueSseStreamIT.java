package com.schale.queue.api.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.schale.queue.api.queue.SubscriptionRegistry.Subscription;
import com.schale.queue.api.queue.dto.AdmissionNotice;
import com.schale.queue.api.queue.dto.QueuePositionResponse;
import com.schale.queue.core.domain.queue.AdmissionTokenService;
import com.schale.queue.core.domain.queue.QueueKeys;
import com.schale.queue.core.domain.queue.QueueProperties;
import com.schale.queue.core.domain.queue.QueueService;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 대기열 SSE 스트림 <b>실동작 e2e 통합테스트</b>(Phase 3 ⑤).
 *
 * <p><b>증명 목표</b>: 실제 Redis 위에서 {@code enqueue → (worker) dequeue + issue → SSE 이벤트}
 * 경로가 성립함을 검증한다. 즉 대기 중에는 {@code position} 이벤트가, 입장 토큰이 발급되면
 * {@code admitted} 이벤트가 나가고 스트림이 종료되는지를 <b>실제 서비스 빈 + 실제 Redis</b>로 확인한다.
 *
 * <p>core 의 {@code QueueIntegrationTest} 와 동일하게, JPA/DB 가 전혀 필요 없으므로 전체 Spring
 * 컨텍스트를 띄우지 않고 {@link LettuceConnectionFactory} 만 직접 구성해 DB 기동에 의존하지 않는다.
 * 인프라가 필요하므로 {@code RUN_REDIS_IT=true} 일 때만 실행된다.
 *
 * <p>SSE 전송은 MVC 비동기 인프라 없이 검증하기 위해, {@link SseEmitter#send(SseEmitter.SseEventBuilder)}
 * 와 {@link SseEmitter#complete()} 를 가로채 기록하는 {@link RecordingSseEmitter} 를 레지스트리에 직접
 * 등록하고 {@link QueueStreamService#broadcast()} 를 호출한다(HTTP 계약은 {@code QueueControllerTest} 가 담당).
 */
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_IT", matches = "true")
class QueueSseStreamIT {

    private static final Long GOODS = 9005L;
    private static final Long MEMBER = 42L;

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private QueueService queueService;
    private AdmissionTokenService admissionService;
    private SubscriptionRegistry registry;
    private QueueStreamService streamService;

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
    void wire() {
        QueueProperties props = new QueueProperties();
        queueService = new QueueService(redis, Clock.systemUTC(), props);
        admissionService = new AdmissionTokenService(redis, props);
        registry = new SubscriptionRegistry();
        streamService = new QueueStreamService(queueService, admissionService, registry, new QueueSseProperties());
    }

    @BeforeEach
    @AfterEach
    void cleanup() {
        redis.delete(QueueKeys.waitingQueue(GOODS));
        redis.delete(QueueKeys.sequence(GOODS));
        redis.opsForSet().remove(QueueKeys.activeGoods(), String.valueOf(GOODS));
        Set<String> admissionKeys = redis.keys("admission:" + GOODS + ":*");
        if (admissionKeys != null && !admissionKeys.isEmpty()) {
            redis.delete(admissionKeys);
        }
    }

    @Test
    @DisplayName("enqueue→dequeue+issue 경로에서 position 이벤트 뒤 admitted 이벤트가 나가고 스트림이 종료된다")
    void streams_position_then_admitted_over_real_redis() {
        // given — 회원이 대기열에 진입(맨 앞, 순번 1)
        assertThat(queueService.enqueue(GOODS, MEMBER)).isTrue();
        assertThat(queueService.getPosition(GOODS, MEMBER)).hasValue(1L);

        // 구독을 등록한다(전송 가로채기용 emitter).
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        registry.add(new Subscription(GOODS, MEMBER), emitter);

        // when ① 아직 대기 중 → 폴링은 position 이벤트를 보낸다.
        streamService.broadcast();

        // then ① 순번 1, 총 대기 1, 스트림 유지.
        QueuePositionResponse position = emitter.firstOf(QueuePositionResponse.class);
        assertThat(position.position()).isEqualTo(1L);
        assertThat(position.waiting()).isEqualTo(1L);
        assertThat(emitter.eventNames()).contains("position");
        assertThat(emitter.completed).isFalse();
        assertThat(registry.size()).isEqualTo(1);

        // when ② Worker 가 하는 일을 그대로 재현: dequeue 로 꺼내고 입장 토큰 발급.
        List<Long> admitted = queueService.dequeue(GOODS, 50);
        assertThat(admitted).containsExactly(MEMBER);
        admitted.forEach(memberId -> assertThat(admissionService.issue(GOODS, memberId)).isTrue());

        // and 다음 폴링 tick.
        streamService.broadcast();

        // then ② admitted 이벤트가 나가고, 스트림이 종료되며, 구독이 정리된다.
        AdmissionNotice notice = emitter.firstOf(AdmissionNotice.class);
        assertThat(notice.goodsId()).isEqualTo(GOODS);
        assertThat(emitter.eventNames()).contains("admitted");
        assertThat(emitter.completed).isTrue();
        assertThat(registry.size()).isZero();
    }

    /**
     * 전송/종료를 기록만 하는 테스트용 emitter. {@code send(SseEventBuilder)} 가 빌더를 풀어
     * {@code super.send(data, mediaType)} 를 직접 부르므로(가상 디스패치 우회), 더 위 단계인
     * {@code send(SseEventBuilder)} 자체를 가로채 보낸 데이터 조각을 모은다.
     */
    private static final class RecordingSseEmitter extends SseEmitter {

        private final List<Object> sent = new CopyOnWriteArrayList<>();
        private volatile boolean completed = false;

        @Override
        public void send(SseEventBuilder builder) {
            for (DataWithMediaType part : builder.build()) {
                sent.add(part.getData());
            }
        }

        @Override
        public void complete() {
            this.completed = true;
        }

        /** 보낸 페이로드 중 주어진 타입의 첫 객체. */
        <T> T firstOf(Class<T> type) {
            return sent.stream().filter(type::isInstance).map(type::cast).findFirst()
                .orElseThrow(() -> new AssertionError(type.getSimpleName() + " 페이로드가 전송되지 않았다: " + sent));
        }

        /**
         * SSE 이벤트 이름 모음. 빌더는 name 과 data 머리말을 한 텍스트 조각으로 합쳐
         * {@code "event:NAME\ndata:"} 형태로 싣는다 — "event:" 뒤부터 첫 줄바꿈까지가 이름이다.
         */
        List<String> eventNames() {
            return sent.stream()
                .filter(String.class::isInstance).map(String.class::cast)
                .filter(s -> s.startsWith("event:"))
                .map(s -> {
                    String afterPrefix = s.substring("event:".length());
                    int newline = afterPrefix.indexOf('\n');
                    return (newline >= 0 ? afterPrefix.substring(0, newline) : afterPrefix).trim();
                })
                .toList();
        }
    }
}
