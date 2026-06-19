package com.schale.queue.api.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI(Swagger) 전역 메타데이터.
 *
 * <p>Swagger UI: {@code /swagger-ui.html} · OpenAPI 문서: {@code /v3/api-docs}
 */
@OpenAPIDefinition(
    info = @Info(
        title = "Schale Queue API",
        version = "v1",
        description = "대용량 트래픽 제어 특화 선착순 예매/커머스 서버 API 명세"
    )
)
@Configuration
public class OpenApiConfig {
}
