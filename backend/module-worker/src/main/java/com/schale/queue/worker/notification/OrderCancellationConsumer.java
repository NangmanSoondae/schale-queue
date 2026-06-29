package com.schale.queue.worker.notification;

import com.schale.queue.core.domain.order.event.OrderCancelledEvent;
import com.schale.queue.core.domain.outbox.ProcessedEvent;
import com.schale.queue.core.domain.outbox.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 취소 이벤트 구독 → 비동기 취소 알림(ADR-002 후방 컨슈머 / ADR-007 멱등).
 *
 * <p>{@code order.cancelled} 토픽을 {@code notification} 컨슈머 그룹으로 구독한다. 주문완료 컨슈머와 동일한
 * 멱등 가드(check → 알림 → 기록)를 적용해, at-least-once 재전달/중복 발행에도 취소 알림이 이중 발송되지 않는다.
 * 멱등 키는 {@code (eventId, consumer_group)} 이며 eventId 는 전역 유니크 UUID 라 주문완료 이벤트와 충돌하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class OrderCancellationConsumer {

    /** 이 컨슈머의 멱등 키 그룹. {@link KafkaListener#groupId()} 와 일치시킨다. */
    static final String GROUP = "notification";

    private static final Logger log = LoggerFactory.getLogger(OrderCancellationConsumer.class);

    private final NotifyGatewayClient notifyGatewayClient;
    private final ProcessedEventRepository processedEventRepository;

    @KafkaListener(topics = OrderCancelledEvent.TOPIC, groupId = GROUP,
        properties = "spring.json.value.default.type=com.schale.queue.core.domain.order.event.OrderCancelledEvent")
    @Transactional
    public void onOrderCancelled(OrderCancelledEvent event) {
        if (processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), GROUP)) {
            log.info("중복 이벤트 무시 eventId={} orderId={} (이미 처리됨)", event.eventId(), event.orderId());
            return;
        }
        log.info("주문취소 이벤트 수신 eventId={} orderId={} reason={}",
            event.eventId(), event.orderId(), event.reason());
        notifyGatewayClient.notifyOrderCancelled(event);
        processedEventRepository.save(ProcessedEvent.of(event.eventId(), GROUP));
    }
}
