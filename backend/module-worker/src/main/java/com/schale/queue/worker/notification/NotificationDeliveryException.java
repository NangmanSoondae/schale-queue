package com.schale.queue.worker.notification;

/**
 * 알림 전송 전 경로(게이트웨이 + 웹훅 폴백) 실패(리뷰 M10).
 *
 * <p>컨슈머가 이 예외를 던지면 컨테이너 에러 핸들러가 지수 백오프로 재시도해 순단을 흡수하고,
 * 소진 시 DLT 로 레코드를 영속 보존한다 — 과거처럼 실패가 조용히 {@code processed_event} 로
 * 확정되어 기록 없이 유실되는 일이 없다. 재시도 중복 발송은 게이트웨이 멱등 키(#30)가 차단한다.
 */
public class NotificationDeliveryException extends RuntimeException {

    public NotificationDeliveryException(String message) {
        super(message);
    }
}
