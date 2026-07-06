package com.schale.queue.worker.health;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 워커 하트비트(리뷰 잔여 '워커 헬스체크 부재').
 *
 * <p>워커는 웹 서버가 없어 HTTP 헬스체크가 불가하고, 릴레이는 <b>유일한 이벤트 발행자</b>라
 * wedge(스케줄러 스레드 고착)를 감지할 수단이 필요하다. 주기적으로 하트비트 파일을 갱신하고,
 * 컨테이너 헬스체크가 <b>파일 mtime 신선도</b>를 검사한다.
 *
 * <p>스프링 기본 {@code TaskScheduler} 는 단일 스레드라 릴레이·만료 워커와 <b>같은 스레드</b>를
 * 공유한다 — 어느 @Scheduled 작업이든 wedge 되면 하트비트도 함께 멈춰 unhealthy 로 드러난다.
 * (별도 스레드로 빼면 "JVM 은 살아있지만 일은 멈춘" 상태를 놓친다 — 공유가 의도된 설계.)
 */
@Component
@RequiredArgsConstructor
public class WorkerHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(WorkerHeartbeat.class);

    private final Clock clock;

    @Value("${schale.worker.heartbeat-file:/tmp/worker-heartbeat}")
    private Path heartbeatFile;

    @Scheduled(fixedDelayString = "${schale.worker.heartbeat-interval:5s}")
    public void beat() {
        try {
            Files.writeString(heartbeatFile, Instant.now(clock).toString());
        } catch (IOException e) {
            // 기록 실패는 헬스체크가 stale mtime 으로 감지한다 — 여기서 죽이지 않는다.
            log.warn("하트비트 기록 실패 path={}", heartbeatFile, e);
        }
    }
}
