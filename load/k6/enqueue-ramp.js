// ============================================================================
//  VT 비교 — 고동시성 enqueue 램프 (블로킹 I/O 고동시성에서 VT 효과 측정)
//  ----------------------------------------------------------------------------
//  enqueue 는 Redis 호출에서 블로킹된다. VU 를 플랫폼 스레드풀(기본 200) 너머로 램프업하면,
//  플랫폼 스레드는 풀 포화로 줄서기(지연↑)·처리량 정체, VT 는 수천 동시 블로킹을 흡수한다.
//  같은 시나리오를 VT on/off 로 돌려 처리량(req/s)·p99 를 대조한다(스레드/메모리는 Actuator 로).
//  실행: docker run --rm -i -e BASE_URL=... -e MAXVUS=1000 grafana/k6 run - < load/k6/enqueue-ramp.js
// ============================================================================
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const GOODS = __ENV.GOODS_ID || '1002';
const MAXVUS = Number(__ENV.MAXVUS || 1000);

const enqueueDuration = new Trend('enqueue_duration', true);

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: Math.round(MAXVUS * 0.2) },  // ~200: 풀 경계
        { duration: '10s', target: Math.round(MAXVUS * 0.5) },  // 풀 초과 진입
        { duration: '15s', target: MAXVUS },                    // 최대 동시성
        { duration: '15s', target: MAXVUS },                    // 유지(정상상태 측정)
        { duration: '5s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],   // p99 를 요약에 노출
};

export default function () {
  const member = String(exec.scenario.iterationInTest + 1);
  const res = http.post(`${BASE}/api/v1/queue/${GOODS}/entries`, null, {
    headers: { 'X-Member-Id': member },
  });
  enqueueDuration.add(res.timings.duration);
  check(res, { 'enqueue 201': (r) => r.status === 201 });
}
