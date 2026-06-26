package com.schale.queue.worker.notification;

import static org.mockito.BDDMockito.then;

import com.schale.queue.core.domain.order.event.OrderCompletedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OrderNotificationConsumer} 단위 테스트 — 수신 이벤트를 알림 클라이언트로 위임하는지 검증.
 */
@ExtendWith(MockitoExtension.class)
class OrderNotificationConsumerTest {

    @Mock
    private NotifyGatewayClient notifyGatewayClient;

    @InjectMocks
    private OrderNotificationConsumer consumer;

    @Test
    @DisplayName("주문완료 이벤트를 수신하면 알림 게이트웨이 클라이언트로 위임한다")
    void delegates_to_notify_client() {
        OrderCompletedEvent event = OrderCompletedEvent.of(1001L, 42L, 38_000L);

        consumer.onOrderCompleted(event);

        then(notifyGatewayClient).should().notifyOrderCompleted(event);
    }
}
