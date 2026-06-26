package com.schale.queue.api.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.schale.queue.core.domain.order.event.OrderCompletedEvent;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * {@link OrderCompletedKafkaPublisher} 단위 테스트 — 커밋 후 브리지가 올바른 토픽/키로 전송하는지 검증.
 */
@ExtendWith(MockitoExtension.class)
class OrderCompletedKafkaPublisherTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private OrderCompletedKafkaPublisher publisher;

    @Test
    @DisplayName("주문완료 이벤트를 order.completed 토픽에 주문 ID 키로 전송한다")
    void publishes_to_topic_with_order_key() {
        OrderCompletedEvent event = OrderCompletedEvent.of(1001L, 42L, 38_000L);
        CompletableFuture<SendResult<String, Object>> future =
            CompletableFuture.completedFuture(new SendResult<>(null, (RecordMetadata) null));
        given(kafkaTemplate.send(eq(OrderCompletedKafkaPublisher.TOPIC), eq("1001"), any()))
            .willReturn(future);

        publisher.onOrderCompleted(event);

        then(kafkaTemplate).should().send(OrderCompletedKafkaPublisher.TOPIC, "1001", event);
    }
}
