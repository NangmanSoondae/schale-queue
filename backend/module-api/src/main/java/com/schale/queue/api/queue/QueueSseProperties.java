package com.schale.queue.api.queue;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSE 대기열 스트림 운영 파라미터(외부화 설정, §5.4.1 하드코딩 금지).
 *
 * <p>{@code schale.queue.sse.*} 프리픽스로 재정의한다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "schale.queue.sse")
public class QueueSseProperties {

    /** 순번/입장 폴링 주기. 너무 짧으면 Redis 부하↑, 너무 길면 체감 지연↑. 기본 1초. */
    private Duration pollInterval = Duration.ofSeconds(1);

    /** SSE 연결 타임아웃. 이 시간 동안 입장하지 못하면 연결을 닫는다(클라이언트 재연결 유도). 기본 30분. */
    private Duration emitterTimeout = Duration.ofMinutes(30);
}
