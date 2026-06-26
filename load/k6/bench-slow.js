// ============================================================================
//  VT 처리량 우위 재현 — 느린(블로킹) 다운스트림에 고동시성 (BenchController /bench/slow)
//  ----------------------------------------------------------------------------
//  요청당 ms 블로킹. 플랫폼 스레드풀(200)이 고갈되는 구간(동시성≫200)에서 VT 처리량 우위가
//  드러난다(enqueue 는 너무 빨라 안 드러났던 부분의 보완). VT on/off 처리량을 대조한다.
//  앱은 schale.bench.enabled=true 로 기동해야 한다.
//  실행: docker run --rm -i -e BASE_URL=... -e MAXVUS=1000 -e SLOW_MS=100 grafana/k6 run - < load/k6/bench-slow.js
// ============================================================================
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const MAXVUS = Number(__ENV.MAXVUS || 1000);
const SLOW_MS = Number(__ENV.SLOW_MS || 100);

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: MAXVUS },
        { duration: '20s', target: MAXVUS },   // 정상상태 처리량 측정
        { duration: '5s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.get(`${BASE}/api/v1/bench/slow?ms=${SLOW_MS}`, { timeout: '30s' });
  check(res, { 'slow 200': (r) => r.status === 200 });
}
