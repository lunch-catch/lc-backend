import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { memberCookie } from '../lib/token.js';

/*
 * 피드를 보내는 두 시나리오(feed.js, feed_impression.js)가 함께 쓰는 조건이다(#1127).
 * 두 시나리오가 같은 램프업, 유지 시간, 회원 대역, 요청 이름으로 돌아야 서로의 결과를 견줄 수 있다.
 *
 * 피드는 RAMP_UP_SECONDS 동안 초당 0건에서 FEED_RATE 건까지 오르고 DURATION 동안 FEED_RATE 건을 유지한다.
 * 결과는 유지 구간(phase:hold)만 센다. 데워지지 않은 JVM 에 첫 초부터 목표 부하를 걸면 처음 쌓인 줄이 끝까지 남는다.
 * 회원은 반복 번호로 돌려 쓴다.
 * 한 회원은 60초에 6번까지 피드를 받으므로 MEMBERS 가 FEED_RATE x 10 보다 커야 429 가 나지 않는다.
 * 같은 캠페인은 한 세션에 한 번만 나와 회원 한 명이 카드 10장을 받는 피드는 캠페인 수 / 10 번뿐이다.
 * 노출 횟수는 그날 내내 남으므로 같은 날 회차를 거듭하면 MEMBER_BASE 를 옮겨 처음 보는 회원을 쓴다.
 */
const BASE_URL = __ENV.BASE_URL;
const SECRET = __ENV.JWT_SECRET;
const FEED_RATE = Number(__ENV.FEED_RATE || 100);
const DURATION = __ENV.DURATION || '5m';
const RAMP_UP_SECONDS = Number(__ENV.RAMP_UP_SECONDS || 60);
const MEMBERS = Number(__ENV.MEMBERS || 10000);
const MEMBER_BASE = Number(__ENV.MEMBER_BASE || 30000001);
// gen_data.py 의 CENTER 와 같다
const CENTER = [37.5563, 126.9220];

export { BASE_URL };

// 목표는 feed-api-design.md 의 "성능 목표" 가 갖는다. 시험을 멈추지 않고 결과에 통과 여부만 남긴다
export const FEED_THRESHOLDS = {
  'http_req_duration{api:feed,phase:hold}': ['p(99)<300'],
  'http_req_failed{api:feed,phase:hold}': ['rate<0.001'],
};

export function feedOptions(thresholds) {
  return {
    scenarios: {
      feed: {
        executor: 'ramping-arrival-rate',
        startRate: 0,
        timeUnit: '1s',
        stages: [
          { duration: `${RAMP_UP_SECONDS}s`, target: FEED_RATE },
          { duration: DURATION, target: FEED_RATE },
        ],
        // 목표대로면 반복 하나가 약 1.3초라 VU 130 이면 되고, v1 처럼 포화되면 약 12.5초라 1,250 이 필요하다
        preAllocatedVUs: Number(__ENV.PRE_VUS || 200),
        maxVUs: Number(__ENV.MAX_VUS || 2000),
      },
    },
    thresholds,
    summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
    /*
     * 기본 시스템 꼬리표에서 url 을 뺀다. 피드 주소에 무작위 좌표가 들어가 url 이 요청마다 달라
     * Prometheus 로 보내는 지표가 요청마다 새 시계열이 된다(#1088). 요청은 name 꼬리표로 묶는다.
     */
    systemTags: ['proto', 'subproto', 'status', 'method', 'name', 'group', 'check', 'error', 'error_code',
      'tls_version', 'scenario', 'service', 'expected_response'],
  };
}

export function feedSetup() {
  if (!BASE_URL || !SECRET) {
    fail('BASE_URL 과 JWT_SECRET 이 필요하다');
  }
  // 시나리오는 setup 이 끝나자마자 시작한다. run.sh 가 이 시각으로 유지 구간의 경계를 계산한다
  console.log(`scenario_start ${Date.now()}`);
}

// 반복 번호로 고른 회원이 피드를 받는다. 200 이 아니면 카드 없이 돌려준다
export function requestFeed() {
  const memberId = MEMBER_BASE + (exec.scenario.iterationInTest % MEMBERS);
  const phase = exec.instance.currentTestRunDuration < RAMP_UP_SECONDS * 1000 ? 'ramp' : 'hold';
  const headers = { Cookie: memberCookie(SECRET, memberId), 'Content-Type': 'application/json' };
  const [lat, lng] = [CENTER[0] + (Math.random() - 0.5) / 111, CENTER[1] + (Math.random() - 0.5) / 88];

  const feed = http.get(`${BASE_URL}/v1/feed?latitude=${lat.toFixed(6)}&longitude=${lng.toFixed(6)}`,
    { headers, tags: { api: 'feed', phase, name: 'GET /v1/feed' } });
  const ok = check(feed, { 'feed 200': (r) => r.status === 200 });
  return { cards: ok ? feed.json('data.cards') || [] : [], headers, lat, lng, phase };
}
