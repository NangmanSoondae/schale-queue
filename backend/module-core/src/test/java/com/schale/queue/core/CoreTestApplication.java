package com.schale.queue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * module-core 통합 테스트 전용 부트 애플리케이션.
 *
 * <p>module-core 는 라이브러리라 자체 진입점이 없으므로, {@code @SpringBootTest} 가
 * 사용할 설정 클래스를 테스트 소스에 둔다.
 *
 * <p>대기열 도메인 서비스(QueueService/AdmissionTokenService)가 {@code StringRedisTemplate}
 * 에 의존하므로 Redis 자동 구성을 더 이상 제외하지 않는다. Lettuce 커넥션은 <b>지연 생성</b>되어
 * 실제 명령 시점에만 연결되므로, JPA 전용 테스트(재고/주문)는 Redis 미사용으로 연결을 시도하지 않는다.
 */
@SpringBootApplication
public class CoreTestApplication {

    /**
     * {@code PaymentService}(아웃박스 직렬화, ADR-007)가 요구하는 ObjectMapper.
     *
     * <p>spring-web 이 없는 컨텍스트에선 Boot 의 Jackson 자동구성이 ObjectMapper 빈을 만들지 않아
     * (Jackson2ObjectMapperBuilder 가 spring-web 클래스 — troubleshooting No.08 과 동일 기전)
     * 컨텍스트 부팅이 실패한다. 워커의 {@code JacksonConfig} 와 같은 규약(JavaTimeModule + ISO 문자열)
     * 으로 명시 제공한다. CI 가 DB IT 를 상시 실행하게 되면서(#25) 비로소 드러난 잠복 결함.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    }
}
