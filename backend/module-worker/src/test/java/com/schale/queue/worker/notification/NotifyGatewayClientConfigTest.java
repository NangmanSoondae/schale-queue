package com.schale.queue.worker.notification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link NotifyGatewayClient} 채널 설정 검증 단위 테스트(리뷰2 M-12/M-13).
 * 잘못된 .env 배포(전체 미설정/부분 설정)가 조용히 진행되지 않는 계약을 고정한다.
 */
class NotifyGatewayClientConfigTest {

    private NotifyGatewayClient client(String url, String key, String webhook, boolean required) {
        NotifyGatewayClient client = new NotifyGatewayClient();
        ReflectionTestUtils.setField(client, "gatewayUrl", url);
        ReflectionTestUtils.setField(client, "apiKey", key);
        ReflectionTestUtils.setField(client, "webhookUrl", webhook);
        ReflectionTestUtils.setField(client, "notifyRequired", required);
        return client;
    }

    @Test
    @DisplayName("required=true + 발송 가능 채널 없음(전체 미설정) → 기동 실패(fail-fast, M-12)")
    void fails_fast_when_required_and_no_channel() {
        assertThatThrownBy(() -> client("", "", "", true).validateChannels())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("알림 채널");
    }

    @Test
    @DisplayName("required=true + 부분 설정(URL만, 키 없음, 웹훅 없음) → 발송 불가로 판정해 기동 실패(M-13)")
    void fails_fast_on_partial_gateway_config_without_fallback() {
        assertThatThrownBy(() -> client("http://gw:8081", "", "", true).validateChannels())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("required=false 면 미설정이어도 기동은 허용된다(로컬 개발 — ERROR 로그만)")
    void tolerates_missing_channels_when_not_required() {
        assertThatCode(() -> client("", "", "", false).validateChannels()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("게이트웨이 완전 설정 또는 웹훅 단독 설정은 정상 통과한다")
    void passes_with_any_deliverable_channel() {
        assertThatCode(() -> client("http://gw:8081", "key", "", true).validateChannels())
            .doesNotThrowAnyException();
        assertThatCode(() -> client("", "", "https://discord/webhook", true).validateChannels())
            .doesNotThrowAnyException();
    }
}
