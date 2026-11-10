import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';
import { ownerCookie } from '../lib/token.js';

/*
 * 점주의 충전(주문 생성과 승인 확정)이 동시에 몰려도 충전이 빠지거나 두 번 들어가지 않는지 본다.
 * 앱은 loadtest 프로필로 띄운다. 결제는 토스 대신 목(LoadTestPaymentGateway)이 정해진 지연 뒤 승인한다.
 *
 * 시나리오는 둘이다. charge 는 주문 하나를 한 번 승인한다. duplicate 는 같은 주문의 승인을 동시에 두 번 보낸다.
 * 점주 수(OWNERS)를 작게 두면 같은 점주의 충전이 겹쳐 점주 계정 행의 잠금 경합이 커진다.
 * 끝나면 k6 요약의 approved_payments, approved_points 를 verify.sql 의 결과와 맞대 본다.
 *
 * 앱은 점주마다 주문과 승인 확정을 합쳐 10초에 3회까지 받고 넘으면 429 로 돌려보낸다(#1122).
 * 429 는 실패로 세지 않고 rate_limited 로 따로 센다. 점주 수가 작은 회차에서는 429 가 나오는 것이 정상이다.
 * 점주 수가 큰 회차는 점주 한 명이 10초에 3회를 넘지 않아야 한다. 초당 반복 수(RATE + DUP_RATE)를 OWNERS 로 나눈 값이 0.1 보다 충분히 작게 둔다.
 *
 * 두 회차를 함께 돌릴 때는 OWNER_OFFSET 으로 점주 대역을 나눈다. 대역이 겹치면 몰린 회차의 상한이 다른 회차의 점주에도 걸린다.
 *
 *   docker exec -i lunchcatch-mysql mysql -uroot -proot lunchcatch < loadtest/billing/seed.sql
 *   k6 run -e BASE_URL=http://localhost:8080 -e JWT_SECRET=... loadtest/billing/charge_concurrency.js
 *   docker exec -i lunchcatch-mysql mysql -uroot -proot lunchcatch < loadtest/billing/verify.sql
 *
 *   # 한 점주에게 몰린 회차(r3)와 여러 점주의 회차(c1)를 함께 돌린다
 *   k6 run ... -e OWNERS=1 -e RATE=50 -e DUP_RATE=20 loadtest/billing/charge_concurrency.js
 *   k6 run ... -e OWNER_OFFSET=1 -e OWNERS=1000 -e RATE=10 -e DUP_RATE=3 -e NO_RATE_LIMIT=true loadtest/billing/charge_concurrency.js
 */
const BASE_URL = __ENV.BASE_URL;
const SECRET = __ENV.JWT_SECRET;
const RATE = Number(__ENV.RATE || 10);
const DUP_RATE = Number(__ENV.DUP_RATE || 2);
const DURATION = __ENV.DURATION || '1m';
const RAMP_UP_SECONDS = Number(__ENV.RAMP_UP_SECONDS || 10);
const OWNERS = Number(__ENV.OWNERS || 100);
// 이 회차가 쓰는 점주 대역의 시작이다. OWNER_OFFSET + OWNERS 가 seed.sql 의 @owners 를 넘지 않아야 한다
const OWNER_OFFSET = Number(__ENV.OWNER_OFFSET || 0);
// seed.sql 의 @lo 와 같다
const OWNER_BASE = 40000001;
// 충전 정책(point_policy)의 상품에 있는 금액이어야 한다. 없으면 주문이 400 이다
const AMOUNT = Number(__ENV.AMOUNT || 10000);

// 응답으로 승인을 확인한 결제의 수와 금액이다. 같은 결제를 두 번 승인해도 한 번만 센다
const approvedPayments = new Counter('approved_payments');
const approvedPoints = new Counter('approved_points');
// 충전 상한에 걸린 요청의 수다. api 꼬리표로 주문과 승인 확정을 나눈다
const rateLimited = new Counter('rate_limited');

// 429 를 http_req_failed 에서 뺀다. 상한에 걸린 것은 서버가 정한 대로 답한 것이고 rate_limited 가 센다
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 429));

function arrival(rate, execName) {
  return {
    executor: 'ramping-arrival-rate',
    exec: execName,
    startRate: 0,
    timeUnit: '1s',
    stages: [
      { duration: `${RAMP_UP_SECONDS}s`, target: rate },
      { duration: DURATION, target: rate },
    ],
    preAllocatedVUs: Number(__ENV.PRE_VUS || 20),
    maxVUs: Number(__ENV.MAX_VUS || 200),
  };
}

export const options = {
  scenarios: {
    charge: arrival(RATE, 'charge'),
    duplicate: arrival(DUP_RATE, 'duplicate'),
  },
  /*
   * 오류율과 응답 검사만 통과 기준으로 건다.
   * 응답 시간 목표는 아직 정하지 않아 API 마다 p95, p99 를 찍기만 한다(max>=0 은 늘 통과한다).
   */
  thresholds: {
    'http_req_failed{phase:hold}': ['rate<0.001'],
    checks: ['rate==1'],
    'http_req_duration{api:order,phase:hold}': ['max>=0'],
    'http_req_duration{api:confirm,phase:hold}': ['max>=0'],
    'http_req_duration{api:confirm_dup,phase:hold}': ['max>=0'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

// 상한에 걸리면 안 되는 회차(점주가 많은 c1)는 NO_RATE_LIMIT=true 로 돌려 429 가 하나라도 나오면 실패로 본다
if (__ENV.NO_RATE_LIMIT === 'true') {
  options.thresholds.rate_limited = ['count==0'];
}

export function setup() {
  if (!BASE_URL || !SECRET) {
    fail('BASE_URL 과 JWT_SECRET 이 필요하다');
  }
}

// seed.sql 의 session_key 와 같은 규칙이다
function sessionKey(ownerId) {
  const hex = ownerId.toString(16);
  return `${hex.padStart(8, '0')}-0000-4000-8000-${hex.padStart(12, '0')}`;
}

function currentPhase() {
  return exec.instance.currentTestRunDuration < RAMP_UP_SECONDS * 1000 ? 'ramp' : 'hold';
}

// 429 면 rate_limited 로 세고 참을 돌려준다. 그 응답은 검사하지 않는다
function limited(res, api) {
  if (res.status !== 429) {
    return false;
  }
  rateLimited.add(1, { api });
  return true;
}

function order(phase) {
  const ownerId = OWNER_BASE + OWNER_OFFSET + (exec.scenario.iterationInTest % OWNERS);
  const headers = { Cookie: ownerCookie(SECRET, ownerId, sessionKey(ownerId)), 'Content-Type': 'application/json' };
  const res = http.post(`${BASE_URL}/v1/owner/payments`, JSON.stringify({ amount: AMOUNT }),
    { headers, tags: { api: 'order', phase } });
  if (limited(res, 'order') || !check(res, { 'order 201': (r) => r.status === 201 })) {
    return null;
  }
  return { headers, paymentId: res.json('data.paymentId'), orderId: res.json('data.orderId') };
}

// 목은 받은 pgTid 를 그대로 승인하므로 주문마다 겹치지 않는 값이면 된다
function confirmRequest(ordered, api, phase) {
  return {
    method: 'POST',
    url: `${BASE_URL}/v1/owner/payments/${ordered.paymentId}:confirm`,
    body: JSON.stringify({ orderId: ordered.orderId, pgTid: `lt_${ordered.orderId}` }),
    params: { headers: ordered.headers, tags: { api, phase } },
  };
}

function approved(res) {
  return check(res, { 'confirm 200 APPROVED': (r) => r.status === 200 && r.json('data.status') === 'APPROVED' });
}

function record() {
  approvedPayments.add(1);
  approvedPoints.add(AMOUNT);
}

export function charge() {
  const phase = currentPhase();
  const ordered = order(phase);
  if (!ordered) {
    return;
  }
  const req = confirmRequest(ordered, 'confirm', phase);
  const res = http.post(req.url, req.body, req.params);
  if (!limited(res, 'confirm') && approved(res)) {
    record();
  }
}

/*
 * 같은 승인 요청 둘을 동시에 보낸다. 상한에 걸리지 않은 쪽은 모두 APPROVED 를 받아야 하고 충전(CHARGE)은 하나만 남아야 한다.
 * 주문과 승인 둘이면 한 반복에 3회라 같은 점주의 다음 요청이 10초 안에 오면 상한에 걸린다.
 */
export function duplicate() {
  const phase = currentPhase();
  const ordered = order(phase);
  if (!ordered) {
    return;
  }
  const req = confirmRequest(ordered, 'confirm_dup', phase);
  const results = http.batch([req, req]).map((res) => !limited(res, 'confirm_dup') && approved(res));
  if (results.some((ok) => ok)) {
    record();
  }
}
