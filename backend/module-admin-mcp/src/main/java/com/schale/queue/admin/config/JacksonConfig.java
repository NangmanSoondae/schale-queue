package com.schale.queue.admin.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 어드민 MCP 용 {@link ObjectMapper} 빈.
 *
 * <p>워커와 같은 이유(#25 교훈): 웹 계층이 없는 모듈은 Boot 의 ObjectMapper 자동구성이 동작하지
 * 않아, core 의 {@code PaymentService}(아웃박스 직렬화) 주입이 실패한다. 워커 JacksonConfig 와
 * 동일 규약(JavaTimeModule + ISO 날짜)으로 명시 등록한다 — 툴 결과의 LocalDateTime 직렬화에도 쓰인다.
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
