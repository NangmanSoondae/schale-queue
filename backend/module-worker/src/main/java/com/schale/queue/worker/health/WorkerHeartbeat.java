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
 * 워커 하트비트(리뷰 잔여 '워커 헬스체크 부재' → 리뷰2 H-2 재설계).
 *
 * <p>워커는 웹 서버가 없어 HTTP 헬스체크가 불가하다. 주기적으로 하트비트 파일을 갱신하고
 * 컨테이너 헬스체크가 <b>파일 mtime 신선도</b>를 검사한다.
 *
 * <p><b>wedge 감지(H-2)</b>: 초기 설계는 "기본 스케줄러 단일 스레드 공유"를 전제로 어떤 작업의
 * 고착이든 하트비트 정지로 이어진다고 봤지만, 워커는 Virtual Threads 활성이라 Boot 가
 * SimpleAsyncTaskScheduler(실행마다 새 가상 스레드)를 써 그 전제가 거짓이었다. 지금은
 * {@link WorkerLiveness} 에 각 @Scheduled 작업이 실행 시작마다 기한을 신고하고, 하트비트는
 * <b>모든 작업의 기한이 유효할 때만</b> 파일을 갱신한다 — 릴레이·만료 워커·큐 컨슈머 중 하나라도
 * 고착되면 파일이 멈추고 컨테이너가 unhealthy 로 떨어진다(프로세스 사망 감지 포함).
 */
@Component
@RequiredArgsConstructor
public class WorkerHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(WorkerHeartbeat.class);

    private final Clock clock;
    private final WorkerLiveness liveness;

    @Value("${schale.worker.heartbeat-file:/tmp/worker-heartbeat}")
    private Path heartbeatFile;

    @Scheduled(fixedDelayString = "${schale.worker.heartbeat-interval:5s}")
    public void beat() {
        if (!liveness.allAlive()) {
            // 파일을 갱신하지 않는 것이 곧 unhealthy 신호다(mtime 이 낡아간다).
            log.warn("하트비트 보류 — stale 작업 감지: [{}]", liveness.staleTasks());
            return;
        }
        try {
            Files.writeString(heartbeatFile, Instant.now(clock).toString());
        } catch (IOException e) {
            // 기록 실패는 헬스체크가 stale mtime 으로 감지한다 — 여기서 죽이지 않는다.
            log.warn("하트비트 기록 실패 path={}", heartbeatFile, e);
        }
    }
}
