// ============================================================================
//  B2 동시성 극한 — 오버셀 0건(S5/S6)·주문 임계구역 지연(S4) (docs/9_nfr_and_slo.md)
//  ----------------------------------------------------------------------------
//  최소재고(10) 상품에 회원 N명이 각자 1회씩 동시 주문. 입장 토큰은 Redis 에 사전 시드.
//  기대: 정확히 10건 201, 나머지 409(STOCK_CONFLICT), 5xx 0건, 오버셀 0.
//  (오버셀 불변식 sold<=total 의 최종 판정은 run.sh 가 DB 에서 수행.)
//    S4 주문 성공 p99 < 200ms
//  실행: docker run --rm -i -e BASE_URL=... -e MEMBERS=1000 -v "$PWD/load/k6:/s" grafana/k6 run /s/order.js
// ============================================================================
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const GOODS = Number(__ENV.GOODS_ID || '1001');
const MEMBERS = Number(__ENV.MEMBERS || 1000);

const successDuration = new Trend('order_success_duration', true);
const success = new Counter('order_success');
const conflict = new Counter('order_conflict');     // 409 품절(정상 거부)
const forbidden = new Counter('order_forbidden');   // 403 입장 토큰 없음
const serverError = new Counter('order_5xx');

export const options = {
  scenarios: {
    burst: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 200),
      iterations: MEMBERS,           // 회원 1명당 정확히 1회 주문 시도
      maxDuration: '60s',
    },
  },
  thresholds: {
    order_success_duration: ['p(99)<200'],   // S4
    order_5xx: ['count==0'],                 // S7: 서버 오류 0건
  },
};

export default function () {
  const member = (exec.scenario.iterationInTest % MEMBERS) + 1;
  const headers = { 'X-Member-Id': String(member), 'Content-Type': 'application/json' };
  const body = JSON.stringify({ goodsId: GOODS, quantity: 1 });

  // 201/409/403 은 '예상된' 상태라 http_req_failed 로 집계되지 않게 한다.
  const res = http.post(`${BASE}/api/v1/orders`, body, {
    headers,
    responseCallback: http.expectedStatuses(201, 409, 403),
  });

  if (res.status === 201) { success.add(1); successDuration.add(res.timings.duration); }
  else if (res.status === 409) conflict.add(1);
  else if (res.status === 403) forbidden.add(1);
  else if (res.status >= 500) serverError.add(1);

  check(res, { 'no 5xx': (r) => r.status < 500 });
}
