package com.schale.queue.api.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.schale.queue.core.domain.order.event.OrderCompletedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * 주문완료 이벤트 Kafka 직렬화 라운드트립 테스트(Docker 불필요, CI 안전).
 *
 * <p>가장 큰 통합 리스크인 <b>JSON 직렬화/역직렬화</b>(특히 {@code LocalDateTime})가 producer 의
 * {@code JsonSerializer} ↔ consumer 의 {@code JsonDeserializer} 사이에서 손실 없이 왕복하는지 검증한다.
 * 실제 브로커 없이 직렬화 계층만 떼어 확인하므로, Kafka 인프라가 없어도 CI 에서 통합 안전망 역할을 한다.
 */
class OrderCompletedEventSerdeTest {

    @Test
    @DisplayName("OrderCompletedEvent 가 JsonSerializer↔JsonDeserializer 로 손실 없이 왕복한다")
    void event_round_trips_through_json_serde() {
        OrderCompletedEvent event = OrderCompletedEvent.of(1001L, 42L, 38_000L);

        try (JsonSerializer<OrderCompletedEvent> serializer = new JsonSerializer<>();
             JsonDeserializer<OrderCompletedEvent> deserializer = new JsonDeserializer<>(OrderCompletedEvent.class)) {
            deserializer.addTrustedPackages("com.schale.queue.*");

            byte[] payload = serializer.serialize(OrderCompletedKafkaPublisher.TOPIC, event);
            OrderCompletedEvent restored = deserializer.deserialize(OrderCompletedKafkaPublisher.TOPIC, payload);

            assertThat(restored).isEqualTo(event);
            assertThat(restored.occurredAt()).isEqualTo(event.occurredAt());   // LocalDateTime 손실 없음
        }
    }
}
