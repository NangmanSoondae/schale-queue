package com.schale.queue.worker.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link WorkerLiveness} + {@link WorkerHeartbeat} 단위 테스트(리뷰2 H-2).
 * "stale 작업이 하나라도 있으면 하트비트 파일이 갱신되지 않는다"는 wedge 감지 계약을 고정한다.
 */
class WorkerLivenessTest {

    private static final Instant T0 = Instant.parse("2026-07-06T00:00:00Z");

    @Test
    @DisplayName("기한 내 재신고가 있으면 allAlive, 기한이 지나면 stale 로 판정한다")
    void reports_alive_until_deadline_passes() {
        MutableClock clock = new MutableClock(T0);
        WorkerLiveness liveness = new WorkerLiveness(clock);

        assertThat(liveness.allAlive()).as("등록 전(부팅 직후)은 alive 취급").isTrue();

        liveness.beat("outbox-relay", Duration.ofSeconds(60));
        liveness.beat("queue-consumer", Duration.ofSeconds(30));
        assertThat(liveness.allAlive()).isTrue();

        clock.advance(Duration.ofSeconds(31));   // queue-consumer 기한(30s)만 초과
        assertThat(liveness.allAlive()).isFalse();
        assertThat(liveness.staleTasks()).isEqualTo("queue-consumer");

        liveness.beat("queue-consumer", Duration.ofSeconds(30));   // 재신고 → 회복
        assertThat(liveness.allAlive()).isTrue();
    }

    @Test
    @DisplayName("stale 작업이 있으면 하트비트 파일을 갱신하지 않는다(=mtime 이 낡아 unhealthy 로 이어짐)")
    void heartbeat_withholds_file_update_when_any_task_stale(@TempDir Path tempDir) throws Exception {
        MutableClock clock = new MutableClock(T0);
        WorkerLiveness liveness = new WorkerLiveness(clock);
        WorkerHeartbeat heartbeat = new WorkerHeartbeat(clock, liveness);
        Path file = tempDir.resolve("heartbeat");
        ReflectionTestUtils.setField(heartbeat, "heartbeatFile", file);

        // 전 작업 정상 → 파일 기록
        liveness.beat("outbox-relay", Duration.ofSeconds(60));
        heartbeat.beat();
        assertThat(file).exists();
        String first = Files.readString(file);

        // 릴레이 wedge(기한 초과) → 파일 미갱신
        clock.advance(Duration.ofSeconds(61));
        heartbeat.beat();
        assertThat(Files.readString(file)).as("stale 상태에선 내용이 그대로여야 한다").isEqualTo(first);

        // 재신고 후 → 다시 갱신
        liveness.beat("outbox-relay", Duration.ofSeconds(60));
        heartbeat.beat();
        assertThat(Files.readString(file)).isNotEqualTo(first);
    }

    /** 테스트에서 시간을 임의로 전진시키는 가변 Clock. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
