package com.schale.queue.worker.settlement;

import com.schale.queue.core.domain.order.event.OrderCancelledEvent;
import com.schale.queue.core.domain.order.event.OrderCompletedEvent;
import com.schale.queue.core.domain.outbox.ProcessedEvent;
import com.schale.queue.core.domain.outbox.repository.ProcessedEventRepository;
import com.schale.queue.core.domain.settlement.SettlementService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 컨슈머 — 주문 완료/취소 이벤트를 구독해 정산 원장을 적재·반제한다(ADR-002 / ADR-007).
 *
 * <p><b>발행측 무변경 슬라이스.</b> 기존 {@code order.completed}/{@code order.cancelled} 토픽을 <b>새</b>
 * 컨슈머 그룹 {@code settlement} 로 구독한다. Kafka 가 {@code notification} 그룹과 독립적으로 메시지 사본을
 * 전달하므로, 알림과 정산은 서로 무영향이고 발행자(주문/결제/아웃박스 릴레이) 코드는 한 줄도 바뀌지 않는다.
 * 이로써 ADR-002 의 "신규 컨슈머 무변경 추가" 확장성을 실증한다.
 *
 * <p><b>멱등.</b> 아웃박스는 at-least-once 라 같은 이벤트가 중복 전달될 수 있어, 알림 컨슈머와 동일한
 * {@code processed_event} 가드를 적용한다: <b>check(이미 처리?) → 정산 반영 → 처리 기록</b>. 멱등 키는
 * {@code (eventId, "settlement")} 로, eventId 가 전역 유니크 UUID 라 {@code notification} 그룹과 충돌하지 않는다.
 *
 * <p><b>exactly-once 효과.</b> 정산은 순수 DB 작업이라 가드 검사·정산 반영·처리 기록이 모두 하나의
 * {@code @Transactional} 안에서 원자적으로 커밋된다. 알림(HTTP·비트랜잭션)과 달리, 재전달 시에도 정산이
 * 정확히 한 번만 반영된다(정산 도메인의 {@code uk_settlement_order_id} 가 2차 방어).
 */
@Component
@RequiredArgsConstructor
public class SettlementConsumer {

    /** 이 컨슈머의 멱등 키 그룹. {@link KafkaListener#groupId()} 와 일치시킨다. */
    static final String GROUP = "settlement";

    private static final Logger log = LoggerFactory.getLogger(SettlementConsumer.class);

    private final SettlementService settlementService;
    private final ProcessedEventRepository processedEventRepository;

    // 릴레이가 보낸 raw JSON 엔 타입 헤더가 없어, 역직렬화 대상 타입을 리스너별로 명시한다(ADR-007, 알림 컨슈머와 동일).
    @KafkaListener(topics = OrderCompletedEvent.TOPIC, groupId = GROUP,
        properties = "spring.json.value.default.type=com.schale.queue.core.domain.order.event.OrderCompletedEvent")
    @Transactional
    public void onOrderCompleted(OrderCompletedEvent event) {
        if (processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), GROUP)) {
            log.info("중복 이벤트 무시 eventId={} orderId={} (이미 처리됨)", event.eventId(), event.orderId());
            return;
        }
        log.info("주문완료 이벤트 수신(정산) eventId={} orderId={}", event.eventId(), event.orderId());
        settlementService.settle(event);
        processedEventRepository.save(ProcessedEvent.of(event.eventId(), GROUP));
    }

    @KafkaListener(topics = OrderCancelledEvent.TOPIC, groupId = GROUP,
        properties = "spring.json.value.default.type=com.schale.queue.core.domain.order.event.OrderCancelledEvent")
    @Transactional
    public void onOrderCancelled(OrderCancelledEvent event) {
        if (processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), GROUP)) {
            log.info("중복 이벤트 무시 eventId={} orderId={} (이미 처리됨)", event.eventId(), event.orderId());
            return;
        }
        log.info("주문취소 이벤트 수신(정산) eventId={} orderId={} reason={}",
            event.eventId(), event.orderId(), event.reason());
        settlementService.reverse(event);
        processedEventRepository.save(ProcessedEvent.of(event.eventId(), GROUP));
    }
}
