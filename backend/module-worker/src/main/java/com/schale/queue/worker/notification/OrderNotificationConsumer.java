package com.schale.queue.worker.notification;

import com.schale.queue.core.domain.order.event.OrderCompletedEvent;
import com.schale.queue.core.domain.outbox.ProcessedEvent;
import com.schale.queue.core.domain.outbox.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 완료 이벤트 구독 → 비동기 알림 발송(UC-08, ADR-002 후방 컨슈머 / ADR-007 멱등).
 *
 * <p>{@code order.completed} 토픽을 {@code notification} 컨슈머 그룹으로 구독한다. 아웃박스는 at-least-once
 * 라 같은 이벤트가 중복 전달될 수 있어, 처리 전에 {@code processed_event} 로 멱등을 가드한다:
 * <b>check(이미 처리?) → 알림 발송 → 처리 기록</b>. 이미 처리된 이벤트는 건너뛴다(이중 발송 차단).
 *
 * <p>알림(HTTP)은 DB 트랜잭션에 묶이지 않는다. 발송 성공 후 기록 커밋이 실패하는 드문 창에서는 재전달 시
 * 알림이 한 번 더 갈 수 있다(at-least-once + 드문 중복). 알림 도메인에서 드문 중복은 수용 가능하다.
 */
@Component
@RequiredArgsConstructor
public class OrderNotificationConsumer {

    /** 이 컨슈머의 멱등 키 그룹. {@link KafkaListener#groupId()} 와 일치시킨다. */
    static final String GROUP = "notification";

    private static final Logger log = LoggerFactory.getLogger(OrderNotificationConsumer.class);

    private final NotifyGatewayClient notifyGatewayClient;
    private final ProcessedEventRepository processedEventRepository;

    // 토픽마다 이벤트 타입이 다르므로(주문완료/취소), 역직렬화 대상 타입을 리스너별로 명시한다.
    // 릴레이가 보낸 raw JSON 엔 타입 헤더가 없어 default.type 으로 결정한다(ADR-007).
    @KafkaListener(topics = OrderCompletedEvent.TOPIC, groupId = GROUP,
        properties = "spring.json.value.default.type=com.schale.queue.core.domain.order.event.OrderCompletedEvent")
    @Transactional
    public void onOrderCompleted(OrderCompletedEvent event) {
        if (processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), GROUP)) {
            log.info("중복 이벤트 무시 eventId={} orderId={} (이미 처리됨)", event.eventId(), event.orderId());
            return;
        }
        log.info("주문완료 이벤트 수신 eventId={} orderId={}", event.eventId(), event.orderId());
        notifyGatewayClient.notifyOrderCompleted(event);
        processedEventRepository.save(ProcessedEvent.of(event.eventId(), GROUP));
    }
}
