// ============================================================================
//  SSE 고연결 관측 — 다수 SSE 구독을 동시에 보유 (async 설계의 스레드 비점유 관측)
//  ----------------------------------------------------------------------------
//  스트림은 끝나지 않으므로 각 VU 의 GET 은 타임아웃까지 '연결을 잡고' 있는다.
//  이 구간에 Actuator 로 스레드 수를 찍어, 연결 수가 늘어도 플랫폼 스레드가 비례 증가하지
//  않음(비동기 서블릿 + 폴러 1개)을 관측한다. (VT 우위 '증명'이 아니라 설계 특성 '관측')
//  실행: docker run --rm -i -e BASE_URL=... -e VUS=500 grafana/k6 run - < load/k6/sse-hold.js
// ============================================================================
import http from 'k6/http';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const GOODS = __ENV.GOODS_ID || '1002';

export const options = {
  scenarios: {
    hold: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 500),
      duration: __ENV.DURATION || '25s',
    },
  },
};

export default function () {
  const member = String(exec.scenario.iterationInTest + 1);
  // 스트림이 끝나지 않으므로 타임아웃까지 연결을 보유한다. 타임아웃 에러는 의도된 동작.
  http.get(`${BASE}/api/v1/queue/${GOODS}/subscribe`, {
    headers: { 'X-Member-Id': member },
    timeout: __ENV.HOLD || '20s',
  });
}
