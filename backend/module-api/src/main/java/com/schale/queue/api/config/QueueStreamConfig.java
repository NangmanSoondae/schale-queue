package com.schale.queue.api.config;

import com.schale.queue.api.queue.QueueSseProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 대기열 SSE 스트림 설정. 폴러용 스케줄링과 SSE 운영 파라미터 바인딩을 활성화한다.
 *
 * <p>{@code @EnableScheduling} 은 {@code QueueStreamService.broadcast()} 의 {@code @Scheduled}
 * 폴링을 구동한다(worker 와 달리 api 는 기본적으로 스케줄링을 켜지 않으므로 여기서 명시 활성화).
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(QueueSseProperties.class)
public class QueueStreamConfig {
}
