// ============================================================================
//  T3 E2E 무유실(S8) — 주문 + 결제 확정 풀플로우 (Phase 5 통합 부하)
//  ----------------------------------------------------------------------------
//  워커가 입장시킨 회원 1..MEMBERS 가 각자 주문(201) 후 즉시 결제 확정(200)한다.
//  이후 run.sh(settlement-check)가 DB 에서 무유실 정합을 판정한다:
//    완료 주문 수 == PAID 결제 수 == settlement 원장 행 수 (Kafka 파이프라인 무유실)
//  실행: ./load/run.sh e2e [VUS] [MEMBERS] [GOODS_ID]
// ============================================================================
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const GOODS = Number(__ENV.GOODS_ID || '1002');
const MEMBERS = Number(__ENV.MEMBERS || 1000);

const orderDuration = new Trend('order_duration', true);
const confirmDuration = new Trend('confirm_duration', true);
const completed = new Counter('flow_completed');    // 주문+확정 모두 성공
const orderRejected = new Counter('order_rejected'); // 403/409 (토큰 없음/품절 — 예상 거부)
const serverError = new Counter('flow_5xx');

export const options = {
  scenarios: {
    e2e: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 100),
      iterations: MEMBERS,           // 회원 1명당 정확히 1회 주문+확정
      maxDuration: '120s',
    },
  },
  thresholds: {
    flow_5xx: ['count==0'],          // S7: 서버 오류 0건
    confirm_duration: ['p(99)<500'], // 참고 지표(결제 확정 임계구역)
  },
};

export default function () {
  const member = (exec.scenario.iterationInTest % MEMBERS) + 1;
  const headers = { 'X-Member-Id': String(member), 'Content-Type': 'application/json' };

  const order = http.post(
    `${BASE}/api/v1/orders`,
    JSON.stringify({ goodsId: GOODS, quantity: 1 }),
    { headers, responseCallback: http.expectedStatuses(201, 403, 409) },
  );
  if (order.status !== 201) {
    if (order.status >= 500) serverError.add(1);
    else orderRejected.add(1);
    return;
  }
  orderDuration.add(order.timings.duration);

  const orderId = order.json('orderId');
  const confirm = http.post(`${BASE}/api/v1/payments/${orderId}/confirm`, null, {
    headers,
    responseCallback: http.expectedStatuses(200),
  });
  confirmDuration.add(confirm.timings.duration);
  if (confirm.status >= 500) serverError.add(1);
  else if (confirm.status === 200) completed.add(1);

  check(confirm, { 'confirm 200': (r) => r.status === 200 });
}
