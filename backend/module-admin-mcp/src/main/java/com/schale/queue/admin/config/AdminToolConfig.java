package com.schale.queue.admin.config;

import com.schale.queue.admin.tools.AdminReadTools;
import com.schale.queue.admin.tools.AdminWriteTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 툴 등록(ADR-009). Spring AI MCP 서버 스타터가 {@link ToolCallbackProvider} 빈을
 * 자동 수집해 MCP {@code tools/list}·{@code tools/call} 로 노출한다.
 *
 * <p><b>쓰기 게이트(ADR-009 §3)</b>: 쓰기 provider 는 {@code schale.admin.write-enabled=true}
 * 일 때만 등록된다 — 기본(false)에서는 tools/list 에조차 나타나지 않아 AI 가 존재를 모른다.
 * 노출 여부를 프롬프트가 아니라 <b>빈 등록 차원</b>에서 끊는 것이 핵심.
 */
@Configuration
public class AdminToolConfig {

    @Bean
    public ToolCallbackProvider adminReadToolProvider(AdminReadTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }

    @Bean
    @ConditionalOnProperty(name = "schale.admin.write-enabled", havingValue = "true")
    public ToolCallbackProvider adminWriteToolProvider(AdminWriteTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
