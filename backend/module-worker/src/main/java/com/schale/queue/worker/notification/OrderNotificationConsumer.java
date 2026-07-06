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
 * <p><b>전송 실패는 던진다(리뷰 M10)</b>: 과거엔 실패가 클라이언트 안에서 삼켜진 채 processed 로 확정되어
 * 게이트웨이 순단 동안의 알림이 기록 없이 유실됐다. 지금은 전 경로(게이트웨이+웹훅) 실패 시
 * {@link NotificationDeliveryException} 을 던져 컨테이너 재시도(지수 백오프 ~2분)로 순단을 흡수하고,
 * 소진 시 DLT 에 영속 보존한다. 재시도로 인한 중복 발송은 게이트웨이 멱등 키가 차단하고,
 * "발송 성공 후 기록 커밋 실패" 창의 드문 재발송도 같은 키로 무해하다.
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
        if (!notifyGatewayClient.notifyOrderCompleted(event)) {
            // processed 마킹 없이 던진다 → 재시도(백오프) → 소진 시 DLT 영속 보존(리뷰 M10).
            throw new NotificationDeliveryException(
                "주문완료 알림 전송 실패 eventId=" + event.eventId() + " orderId=" + event.orderId());
        }
        processedEventRepository.save(ProcessedEvent.of(event.eventId(), GROUP));
    }
}
