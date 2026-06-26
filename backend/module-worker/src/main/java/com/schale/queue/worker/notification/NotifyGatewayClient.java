package com.schale.queue.worker.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schale.queue.core.domain.order.event.OrderCompletedEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 알림 게이트웨이 호출 클라이언트(UC-08, 행동강령 §5.3.4 패턴).
 *
 * <p>1차로 공용 알림 게이트웨이 API({@code POST /api/v1/notifications}, {@code to=schale-ops})를 호출하고,
 * 도달 불가하면 Discord 웹훅 직접 호출로 폴백한다(ADR-003 §4 롤백 안전망). 시크릿(게이트웨이 URL/Key·웹훅
 * URL)은 환경변수에서만 로드한다(§5.3.2). 웹 서버 의존(tomcat) 없이 JDK 내장 {@link HttpClient} 를 쓴다.
 */
@Component
public class NotifyGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(NotifyGatewayClient.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${NOTIFY_GATEWAY_URL:}")
    private String gatewayUrl;
    @Value("${NOTIFY_GATEWAY_API_KEY:}")
    private String apiKey;
    @Value("${DISCORD_WEBHOOK_URL:}")
    private String webhookUrl;

    /** 주문 완료 알림을 전송한다. 1차 게이트웨이 → 실패 시 웹훅 폴백 → 둘 다 불가하면 건너뛴다(작업 흐름 무영향). */
    public void notifyOrderCompleted(OrderCompletedEvent event) {
        String message = String.format("🎫 주문 #%d 결제 완료 (회원 %d, 금액 %,d원)",
            event.orderId(), event.memberId(), event.totalAmount());
        if (sendViaGateway(message)) {
            return;
        }
        if (sendViaWebhook(message)) {
            return;
        }
        log.warn("알림 전송 경로 없음(게이트웨이·웹훅 미설정/도달불가) — 건너뜀. orderId={}", event.orderId());
    }

    private boolean sendViaGateway(String message) {
        if (gatewayUrl.isBlank() || apiKey.isBlank()) {
            return false;
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                "channel", "discord", "to", "schale-ops", "title", "[주문 완료]", "message", message));
            HttpRequest req = HttpRequest.newBuilder(URI.create(gatewayUrl + "/api/v1/notifications"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() / 100 == 2) {
                log.info("알림 전송(게이트웨이) 성공 status={}", res.statusCode());
                return true;
            }
            log.warn("알림 전송(게이트웨이) 비정상 응답 status={}", res.statusCode());
            return false;
        } catch (Exception e) {
            log.warn("알림 전송(게이트웨이) 실패 — 폴백 시도", e);
            return false;
        }
    }

    private boolean sendViaWebhook(String message) {
        if (webhookUrl.isBlank()) {
            return false;
        }
        try {
            String body = mapper.writeValueAsString(Map.of("content", "🤖 " + message));
            HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            boolean ok = res.statusCode() / 100 == 2;
            log.info("알림 전송(웹훅 폴백) status={}", res.statusCode());
            return ok;
        } catch (Exception e) {
            log.warn("알림 전송(웹훅 폴백) 실패", e);
            return false;
        }
    }
}
