package com.schale.queue.worker.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schale.queue.core.domain.order.event.OrderCompletedEvent;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.JacksonUtils;
import org.springframework.kafka.support.serializer.JsonDeserializer;

/**
 * 아웃박스 릴레이 → 컨슈머 직렬화 라운드트립 테스트(ADR-007, Docker 불필요·CI 안전).
 *
 * <p>실제 경로를 떼어 검증한다: <b>발행측 ObjectMapper 가 이벤트를 JSON 문자열로 직렬화</b>(릴레이가 그 문자열을
 * StringSerializer 로 그대로 전송) → <b>컨슈머의 JsonDeserializer 가 raw JSON 바이트를 역직렬화</b>(타입 헤더
 * 없이 default 타입 사용). 가장 큰 통합 리스크인 {@code LocalDateTime}(occurredAt)이 손실 없이 왕복하는지,
 * 그리고 워커 클래스패스에 jsr310 이 있어 역직렬화가 성공하는지를 함께 확인한다(머지된 슬라이스의 잠재 누락 방지).
 */
class OutboxEventSerdeTest {

    @Test
    @DisplayName("ObjectMapper 직렬화 JSON 이 JsonDeserializer 로 손실 없이 역직렬화된다(LocalDateTime 포함)")
    void json_string_round_trips_through_consumer_deserializer() throws Exception {
        OrderCompletedEvent event = OrderCompletedEvent.of(1001L, 42L, 38_000L);

        // 발행측: PaymentService 가 쓰는 것과 동등한(jsr310·ISO 날짜) ObjectMapper 로 payload 직렬화
        ObjectMapper mapper = JacksonUtils.enhancedObjectMapper();
        String payload = mapper.writeValueAsString(event);

        // 구독측: 워커 consumer 설정과 동일 — 타입 헤더 무시 + default 타입 OrderCompletedEvent
        try (JsonDeserializer<OrderCompletedEvent> deserializer =
                 new JsonDeserializer<>(OrderCompletedEvent.class).ignoreTypeHeaders()) {
            deserializer.addTrustedPackages("com.schale.queue.*");

            OrderCompletedEvent restored =
                deserializer.deserialize(OrderCompletedEvent.TOPIC, payload.getBytes(StandardCharsets.UTF_8));

            assertThat(restored).isEqualTo(event);
            assertThat(restored.occurredAt()).isEqualTo(event.occurredAt());   // LocalDateTime 손실 없음
        }
    }
}
