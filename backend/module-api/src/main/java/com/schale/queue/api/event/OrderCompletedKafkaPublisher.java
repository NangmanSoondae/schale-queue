package com.schale.queue.api.event;

import com.schale.queue.core.domain.order.event.OrderCompletedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 주문 완료 도메인 이벤트를 Kafka 토픽으로 내보내는 브리지(ADR-002 후방 파이프라인).
 *
 * <p>core 가 발행한 {@link OrderCompletedEvent}(ApplicationEvent)를 <b>트랜잭션 커밋 후</b>
 * ({@link TransactionPhase#AFTER_COMMIT}) Kafka {@code order.completed} 토픽으로 보낸다.
 * 커밋 전 발행을 막아, 롤백된 주문의 팬텀 이벤트가 나가지 않게 한다(발행 신뢰성 = AFTER_COMMIT).
 *
 * <p>⚠️ 커밋은 됐는데 Kafka 전송이 실패하면 이벤트가 유실될 수 있다(at-most-once 한계). 무유실(S8)은
 * 후속 트랜잭셔널 아웃박스 슬라이스에서 보강한다(ADR-002 §3.4). 현재는 전송 실패를 로그로 남긴다.
 */
@Component
@RequiredArgsConstructor
public class OrderCompletedKafkaPublisher {

    public static final String TOPIC = "order.completed";

    private static final Logger log = LoggerFactory.getLogger(OrderCompletedKafkaPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCompleted(OrderCompletedEvent event) {
        // 파티션 내 순서 보장을 위해 주문 ID 를 메시지 키로 쓴다(같은 주문 이벤트는 같은 파티션).
        kafkaTemplate.send(TOPIC, String.valueOf(event.orderId()), event)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("주문완료 이벤트 발행 실패 eventId={} orderId={}", event.eventId(), event.orderId(), ex);
                } else {
                    log.info("주문완료 이벤트 발행 eventId={} orderId={}", event.eventId(), event.orderId());
                }
            });
    }
}
