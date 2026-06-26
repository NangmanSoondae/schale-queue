// ============================================================================
//  B1 기준부하 — 대기열 진입(S1)·순번 조회(S2) 지연 측정 (docs/9_nfr_and_slo.md)
//  ----------------------------------------------------------------------------
//  각 VU 반복: enqueue(POST entries) → position(GET position).
//  enqueue/position 지연을 별도 Trend 로 분리해 SLO threshold 로 합격/불합격 판정.
//    S1 enqueue p99 < 100ms · S2 position p99 < 50ms
//  실행: docker run --rm -i -e BASE_URL=... -v "$PWD/load/k6:/s" grafana/k6 run /s/queue.js
// ============================================================================
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const GOODS = __ENV.GOODS_ID || '1002';

const enqueueDuration = new Trend('enqueue_duration', true);
const positionDuration = new Trend('position_duration', true);

export const options = {
  scenarios: {
    queue: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 100),
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    enqueue_duration: ['p(99)<100'],   // S1
    position_duration: ['p(99)<50'],   // S2
    http_req_failed: ['rate<0.001'],   // 오류율(참고)
  },
};

export default function () {
  // 전역적으로 유일한 회원 id → 대기열이 계속 커지며 ZRANK O(log N) 특성을 측정.
  const member = String(exec.scenario.iterationInTest + 1);
  const headers = { 'X-Member-Id': member };

  const enq = http.post(`${BASE}/api/v1/queue/${GOODS}/entries`, null, { headers });
  enqueueDuration.add(enq.timings.duration);
  check(enq, { 'enqueue 201': (r) => r.status === 201 });

  const pos = http.get(`${BASE}/api/v1/queue/${GOODS}/position`, { headers });
  positionDuration.add(pos.timings.duration);
  check(pos, { 'position 200': (r) => r.status === 200 });
}
