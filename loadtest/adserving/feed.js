import { FEED_THRESHOLDS, feedOptions, feedSetup, requestFeed } from './feed_common.js';

/*
 * 피드만 보낸다(#1127). 서빙을 고친 효과를 가르는 시나리오다.
 * feed_impression.js 는 노출 저장의 부하가 피드 p99 에 섞여 서빙만의 크기를 가릴 수 없다.
 * 조건(램프업, 유지 시간, 회원 대역, 요청 이름)은 feed_common.js 가 갖는다.
 */
export const options = feedOptions(FEED_THRESHOLDS);

export function setup() {
  feedSetup();
}

export default function () {
  requestFeed();
}
