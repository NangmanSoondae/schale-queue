package com.schale.queue.worker.notification;

import com.schale.queue.core.domain.order.event.OrderCompletedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 주문 완료 이벤트 구독 → 비동기 알림 발송(UC-08, ADR-002 후방 컨슈머).
 *
 * <p>{@code order.completed} 토픽을 {@code notification} 컨슈머 그룹으로 구독한다. at-least-once 전달이라
 * 중복 수신될 수 있으나, 알림은 드문 중복이 치명적이지 않다(엄격 멱등은 무유실 보강 슬라이스에서 eventId 기준 dedup).
 */
@Component
@RequiredArgsConstructor
public class OrderNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderNotificationConsumer.class);

    private final NotifyGatewayClient notifyGatewayClient;

    @KafkaListener(topics = "order.completed", groupId = "notification")
    public void onOrderCompleted(OrderCompletedEvent event) {
        log.info("주문완료 이벤트 수신 eventId={} orderId={}", event.eventId(), event.orderId());
        notifyGatewayClient.notifyOrderCompleted(event);
    }
}
