package com.schale.queue.admin.config;

import com.schale.queue.admin.tools.AdminReadTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 툴 등록(ADR-009). Spring AI MCP 서버 스타터가 {@link ToolCallbackProvider} 빈을
 * 자동 수집해 MCP {@code tools/list}·{@code tools/call} 로 노출한다.
 *
 * <p>2차(쓰기 툴)는 {@code schale.admin.write-enabled} 게이트가 걸린 별도 provider 로 추가한다.
 */
@Configuration
public class AdminToolConfig {

    @Bean
    public ToolCallbackProvider adminReadToolProvider(AdminReadTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
