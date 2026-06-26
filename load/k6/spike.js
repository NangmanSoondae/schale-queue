// ============================================================================
//  B4 스파이크 — 오픈 순간 전원 t=0 버스트 (전방 큐 견딤, S1/S7)
//  ----------------------------------------------------------------------------
//  거의 즉시 MAXVUS 까지 치솟아 enqueue 를 두드린다(선착순 오픈 현실). 큐(ZADD)가 순간 폭주를
//  평탄화하는지 — enqueue 지연(S1)·에러율(S7)을 본다.
//  실행: docker run --rm -i -e BASE_URL=... -e MAXVUS=1000 grafana/k6 run - < load/k6/spike.js
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
    spike: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '2s', target: MAXVUS },    // 거의 즉시 폭주(t=0 버스트)
        { duration: '10s', target: MAXVUS },   // 유지
        { duration: '2s', target: 0 },
      ],
      gracefulRampDown: '3s',
    },
  },
  thresholds: {
    enqueue_duration: ['p(99)<100'],   // S1 (스파이크에서도 유지 목표)
    http_req_failed: ['rate<0.001'],   // S7 에러율
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const member = String(exec.scenario.iterationInTest + 1);
  const res = http.post(`${BASE}/api/v1/queue/${GOODS}/entries`, null, {
    headers: { 'X-Member-Id': member },
  });
  enqueueDuration.add(res.timings.duration);
  check(res, { 'enqueue 201': (r) => r.status === 201 });
}
