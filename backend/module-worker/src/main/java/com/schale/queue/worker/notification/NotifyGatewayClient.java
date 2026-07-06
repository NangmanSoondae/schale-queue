package com.schale.queue.worker.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schale.queue.core.domain.order.event.OrderCancelledEvent;
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
 *
 * <p><b>게이트웨이 멱등성(게이트웨이 README §2)</b>: Kafka 컨슈머 경유 알림은 at-least-once 재전달로
 * 같은 이벤트가 다시 올 수 있고, 로컬 멱등 가드(processed_event)에도 "발송 성공 후 기록 커밋 실패" 창이
 * 남는다. 이벤트의 안정 식별자(eventId — 재전달에도 동일)에서 유도한 {@code Idempotency-Key} 를 붙여
 * 그 창까지 게이트웨이 측에서 닫는다(중복 요청은 재발송 없이 {@code Idempotency-Replayed: true} 재현).
 * 키는 요청 내용이 아니라 <b>비즈니스 이벤트당 유일</b>해야 하므로 랜덤 UUID 신규 생성은 금지.
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

    /**
     * 주문 완료 알림을 전송한다. 1차 게이트웨이 → 실패 시 웹훅 폴백.
     * 결제/정산성 상태 전이 알림이라 중복 발송이 해로움 — eventId 유도 멱등 키를 붙인다.
     *
     * @return 어느 경로로든 전송이 확인되면 {@code true}. {@code false} 면 호출측(컨슈머)이 실패를
     *         확정 처리하지 말아야 한다(리뷰 M10 — 과거엔 실패가 조용히 삼켜진 뒤 processed 마킹됐다).
     */
    public boolean notifyOrderCompleted(OrderCompletedEvent event) {
        String message = String.format("🎫 주문 #%d 결제 완료 (회원 %d, 금액 %,d원)",
            event.orderId(), event.memberId(), event.totalAmount());
        return notify("[주문 완료]", message, "order-completed-" + event.eventId(), event.orderId());
    }

    /** 주문 취소 알림을 전송한다(결제 만료 등). 전송 경로/폴백/멱등/반환 정책은 주문 완료와 동일하다. */
    public boolean notifyOrderCancelled(OrderCancelledEvent event) {
        String message = String.format("❌ 주문 #%d 취소 (회원 %d, 사유 %s)",
            event.orderId(), event.memberId(), event.reason());
        return notify("[주문 취소]", message, "order-cancelled-" + event.eventId(), event.orderId());
    }

    /**
     * 1차 게이트웨이 → 실패 시 웹훅 폴백. 둘 다 실패하면 {@code false} 를 반환해 호출측이
     * 재시도/DLT 로 이어가게 한다(리뷰 M10). 두 경로 모두 '미설정'(로컬 개발 등)이면 발송할 곳이
     * 없다는 뜻이므로 경고만 남기고 {@code true} 를 반환한다(재시도해도 결과가 같아 무의미).
     */
    private boolean notify(String title, String message, String idempotencyKey, Long orderId) {
        if (gatewayUrl.isBlank() && webhookUrl.isBlank()) {
            log.warn("알림 채널 미설정(게이트웨이·웹훅 모두) — 발송 생략. orderId={}", orderId);
            return true;
        }
        if (sendViaGateway(title, message, idempotencyKey, false)) {
            return true;
        }
        if (sendViaWebhook(message)) {
            return true;
        }
        log.warn("알림 전송 전 경로 실패(게이트웨이·웹훅) — 호출측 재시도 필요. orderId={}", orderId);
        return false;
    }

    /**
     * 게이트웨이 호출. 옵션:
     *
     * @param idempotencyKey 비즈니스 이벤트당 유일한 멱등 키(게이트웨이 TTL 24h). {@code null} 이면 미적용.
     * @param async {@code true} 면 {@code Prefer: respond-async} — 202 즉시 수신 후 게이트웨이 백그라운드
     *              릴레이가 발송한다(요청 ID 는 Location 헤더, 로그로만 남긴다). 응답을 기다릴 필요 없고
     *              자체 재시도 보증도 없는 fire-and-forget 경로 전용.
     */
    private boolean sendViaGateway(String title, String message, String idempotencyKey, boolean async) {
        if (gatewayUrl.isBlank() || apiKey.isBlank()) {
            return false;
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                "channel", "discord", "to", "schale-ops", "title", title, "message", message));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(gatewayUrl + "/api/v1/notifications"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body));
            if (idempotencyKey != null) {
                builder.header("Idempotency-Key", idempotencyKey);
            }
            if (async) {
                builder.header("Prefer", "respond-async");
            }
            HttpResponse<Void> res = http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() / 100 == 2) {
                boolean replayed = res.headers().firstValue("Idempotency-Replayed").map(Boolean::parseBoolean).orElse(false);
                String location = res.headers().firstValue("Location").orElse(null);
                log.info("알림 전송(게이트웨이) 성공 status={} idempotencyKey={} replayed={}{}",
                    res.statusCode(), idempotencyKey, replayed,
                    location != null ? " location=" + location : "");
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
