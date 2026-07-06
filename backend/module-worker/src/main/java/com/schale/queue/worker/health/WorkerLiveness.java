package com.schale.queue.worker.health;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 워커 @Scheduled 작업들의 <b>작업별 생존 신고</b> 레지스트리(리뷰2 H-2).
 *
 * <p>과거 하트비트는 "기본 스케줄러 = 단일 스레드 공유 → 아무 작업이나 wedge 되면 하트비트도
 * 멈춤"을 전제했지만, 워커는 Virtual Threads 활성으로 Boot 가 SimpleAsyncTaskScheduler(실행마다
 * 새 가상 스레드)를 쓰므로 그 전제가 거짓이었다 — 릴레이가 고착돼도 healthy 로 남았다.
 *
 * <p>대신 각 작업이 실행 시작 시 {@link #beat} 로 "다음 신고 기한(deadline)"을 갱신하고,
 * 하트비트 기록자는 <b>모든 등록 작업의 기한이 유효할 때만</b> 파일을 갱신한다. 어떤 작업이든
 * 실행 중 고착되면 자기 기한을 더는 못 미뤄 만료되고 → 하트비트 파일이 멈추고 → 컨테이너
 * 헬스체크(mtime 신선도)가 unhealthy 로 판정한다. VT 의 작업 격리 이점은 그대로 유지된다.
 */
@Component
@RequiredArgsConstructor
public class WorkerLiveness {

    private final Clock clock;

    /** 작업명 → 이 시각을 넘기면 stale 로 간주할 기한. */
    private final Map<String, Instant> deadlines = new ConcurrentHashMap<>();

    /**
     * 작업 실행 시작을 신고한다. 기한은 {@code now + staleAfter} — staleAfter 는 해당 작업의
     * "정상적으로 다시 돌아올 수 있는 최대 시간"(주기 + 최악 실행 시간 + 여유)으로 잡는다.
     */
    public void beat(String task, Duration staleAfter) {
        deadlines.put(task, Instant.now(clock).plus(staleAfter));
    }

    /**
     * 등록된 모든 작업의 기한이 아직 유효한가. 부팅 직후(등록 전) 빈 상태는 살아있는 것으로
     * 간주한다 — 컨테이너 헬스체크의 start_period 가 그 구간을 가린다.
     *
     * @return stale 작업이 하나도 없으면 {@code true}
     */
    public boolean allAlive() {
        Instant now = Instant.now(clock);
        return deadlines.values().stream().allMatch(deadline -> deadline.isAfter(now));
    }

    /** stale 상태인 작업명 목록(경고 로그용). */
    public String staleTasks() {
        Instant now = Instant.now(clock);
        return deadlines.entrySet().stream()
            .filter(entry -> !entry.getValue().isAfter(now))
            .map(Map.Entry::getKey)
            .reduce((a, b) -> a + "," + b)
            .orElse("");
    }
}
