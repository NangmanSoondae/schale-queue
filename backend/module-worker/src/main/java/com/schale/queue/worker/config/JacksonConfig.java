package com.schale.queue.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 워커용 {@link ObjectMapper} 빈(ADR-007).
 *
 * <p>Spring Boot 의 ObjectMapper 자동구성은 spring-web 의 {@code Jackson2ObjectMapperBuilder} 에 의존한다.
 * 워커는 웹 계층이 없어(spring-web 부재) 자동 ObjectMapper 빈이 생성되지 않으므로, core 의 {@code PaymentService}
 * (아웃박스 직렬화에 ObjectMapper 필요)가 워커 컨텍스트에서 주입 실패한다. 이를 명시 빈으로 보완한다.
 *
 * <p>{@code LocalDateTime}(이벤트 occurredAt) 직렬화를 위해 {@link JavaTimeModule} 을 등록하고, 날짜를
 * 타임스탬프가 아닌 ISO 문자열로 쓴다(컨슈머 JsonDeserializer 와 동일 규약 — 라운드트립 무손실).
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    }
}
