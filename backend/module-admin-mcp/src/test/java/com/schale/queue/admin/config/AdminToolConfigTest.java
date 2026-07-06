package com.schale.queue.admin.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.schale.queue.admin.tools.AdminReadTools;
import com.schale.queue.admin.tools.AdminWriteTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 쓰기 툴 게이트 테스트(ADR-009 §3): {@code schale.admin.write-enabled} 가 꺼져 있으면
 * 쓰기 provider 빈 자체가 등록되지 않아 MCP tools/list 에 노출될 수 없음을 고정한다.
 */
class AdminToolConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(AdminToolConfig.class)
        // 툴 빈은 실제 인스턴스(의존성 null) — provider 는 어노테이션만 스캔하고 호출하지 않는다.
        .withBean(AdminReadTools.class, () -> new AdminReadTools(null, null, null, null, null))
        .withBean(AdminWriteTools.class, () -> new AdminWriteTools(null, null, null));

    @Test
    @DisplayName("기본(미설정) = 읽기 전용 — 쓰기 provider 미등록")
    void write_provider_absent_by_default() {
        runner.run(context -> {
            assertThat(context).hasBean("adminReadToolProvider");
            assertThat(context).doesNotHaveBean("adminWriteToolProvider");
        });
    }

    @Test
    @DisplayName("write-enabled=true 일 때만 쓰기 provider 가 등록된다")
    void write_provider_present_when_enabled() {
        runner.withPropertyValues("schale.admin.write-enabled=true").run(context -> {
            assertThat(context).hasBean("adminReadToolProvider");
            assertThat(context).hasBean("adminWriteToolProvider");
        });
    }
}
