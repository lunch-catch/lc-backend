import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, FEED_THRESHOLDS, feedOptions, feedSetup, requestFeed } from './feed_common.js';

/*
 * 피드를 받고 받은 카드마다 노출을 보낸다(#997). 쓰기 구조의 버전을 같은 조건으로 비교하는 시나리오다.
 * 노출 수집과 서빙 로그 두 쓰기가 함께 부하를 받는다(docs/design/adserving/adserving_요청_중_쓰기_설계.md).
 * 조건(램프업, 유지 시간, 회원 대역, 요청 이름)은 feed_common.js 가 갖는다.
 */
const impressionResults = new Counter('impression_results');

export const options = feedOptions({
  ...FEED_THRESHOLDS,
  'http_req_duration{api:impression,phase:hold}': ['p(99)<500'],
  'http_req_failed{api:impression,phase:hold}': ['rate<0.001'],
  'impression_results{phase:hold}': ['count>=0'],
});

export function setup() {
  feedSetup();
}

export default function () {
  const { cards, headers, lat, lng, phase } = requestFeed();

  for (const card of cards) {
    const res = http.post(`${BASE_URL}/v1/impressions`, JSON.stringify({
      serveId: card.serveId,
      campaignId: card.campaignId,
      displayedAt: new Date().toISOString(),
      latitude: lat,
      longitude: lng,
      viewedMs: 1500,
    }), { headers, tags: { api: 'impression', phase, name: 'POST /v1/impressions' } });
    if (check(res, { 'impression 202': (r) => r.status === 202 })) {
      impressionResults.add(1, { validity: res.json('data.validity'), phase });
    }
  }
}
