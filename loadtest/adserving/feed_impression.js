import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';
import { memberCookie } from '../lib/token.js';

/*
 * 피드를 받고 받은 카드마다 노출을 보낸다(#997). 쓰기 구조의 버전을 같은 조건으로 비교하는 시나리오다.
 * 노출 수집과 서빙 로그 두 쓰기가 함께 부하를 받는다(docs/design/adserving/adserving_요청_중_쓰기_설계.md).
 *
 * 피드는 FEED_RATE 건/초로 고르게 들어온다. 회원은 반복 번호로 돌려 쓴다.
 * 한 회원은 60초에 6번까지 피드를 받으므로 MEMBERS 가 FEED_RATE x 10 보다 커야 429 가 나지 않는다.
 * 같은 캠페인은 한 세션에 한 번만 나와 회원 한 명이 카드 10장을 받는 피드는 캠페인 수 / 10 번뿐이다.
 * 노출 횟수는 그날 내내 남으므로 같은 날 회차를 거듭하면 MEMBER_BASE 를 옮겨 처음 보는 회원을 쓴다.
 */
const BASE_URL = __ENV.BASE_URL;
const SECRET = __ENV.JWT_SECRET;
const FEED_RATE = Number(__ENV.FEED_RATE || 500);
const DURATION = __ENV.DURATION || '5m';
const MEMBERS = Number(__ENV.MEMBERS || 10000);
const MEMBER_BASE = Number(__ENV.MEMBER_BASE || 30000001);
// gen_adserving.py 의 CENTER 와 같다
const CENTER = [37.5563, 126.9220];

const impressionResults = new Counter('impression_results');

export const options = {
  scenarios: {
    feed: {
      executor: 'constant-arrival-rate',
      rate: FEED_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Number(__ENV.PRE_VUS || FEED_RATE * 2),
      maxVUs: Number(__ENV.MAX_VUS || FEED_RATE * 6),
    },
  },
  // 목표는 feed-api-design.md 의 "성능 목표" 가 갖는다. 시험을 멈추지 않고 결과에 통과 여부만 남긴다
  thresholds: {
    'http_req_duration{api:feed}': ['p(99)<500'],
    'http_req_duration{api:impression}': ['p(99)<200'],
    'http_req_failed{api:feed}': ['rate<0.001'],
    'http_req_failed{api:impression}': ['rate<0.001'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  if (!BASE_URL || !SECRET) {
    fail('BASE_URL 과 JWT_SECRET 이 필요하다');
  }
}

export default function () {
  const memberId = MEMBER_BASE + (exec.scenario.iterationInTest % MEMBERS);
  const headers = { Cookie: memberCookie(SECRET, memberId), 'Content-Type': 'application/json' };
  const [lat, lng] = [CENTER[0] + (Math.random() - 0.5) / 111, CENTER[1] + (Math.random() - 0.5) / 88];

  const feed = http.get(`${BASE_URL}/v1/feed?latitude=${lat.toFixed(6)}&longitude=${lng.toFixed(6)}`,
    { headers, tags: { api: 'feed' } });
  if (!check(feed, { 'feed 200': (r) => r.status === 200 })) {
    return;
  }
  const cards = feed.json('data.cards') || [];

  for (const card of cards) {
    const res = http.post(`${BASE_URL}/v1/impressions`, JSON.stringify({
      serveId: card.serveId,
      campaignId: card.campaignId,
      displayedAt: new Date().toISOString(),
      latitude: lat,
      longitude: lng,
      viewedMs: 1500,
    }), { headers, tags: { api: 'impression' } });
    if (check(res, { 'impression 202': (r) => r.status === 202 })) {
      impressionResults.add(1, { validity: res.json('data.validity') });
    }
  }
}
