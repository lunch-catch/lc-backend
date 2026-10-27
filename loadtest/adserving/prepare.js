import http from 'k6/http';
import { sleep, fail } from 'k6';
import { adminCookie } from '../lib/token.js';

/*
 * 생성기가 FAILED 로 둔 그날 00:00 묶음을 관리자 재실행 API 로 다시 돌리고 끝날 때까지 기다린다(#997).
 * 묶음이 예약, 캠페인 활성화, 그날 후보 적재를 만든다. 부하 시나리오보다 먼저 한 번 돈다.
 *
 *   k6 run -e BASE_URL=http://localhost:8080 -e JWT_SECRET=... -e BUSINESS_DATE=2026-10-27 loadtest/adserving/prepare.js
 */
const BASE_URL = __ENV.BASE_URL;
const DATE = __ENV.BUSINESS_DATE;
const ADMIN_ID = Number(__ENV.ADMIN_ID || 30000001);
const WAIT_SECONDS = Number(__ENV.WAIT_SECONDS || 600);

export const options = { vus: 1, iterations: 1 };

export default function () {
  if (!BASE_URL || !__ENV.JWT_SECRET || !DATE) {
    fail('BASE_URL, JWT_SECRET, BUSINESS_DATE 가 필요하다');
  }
  const headers = { Cookie: adminCookie(__ENV.JWT_SECRET, ADMIN_ID) };
  const retry = http.post(`${BASE_URL}/v1/admin/batch-executions/DAILY_0000/${DATE}:retry`, null, { headers });
  if (retry.status !== 202) {
    fail(`재실행 접수 실패: ${retry.status} ${retry.body}`);
  }

  const query = `from=${DATE}&to=${DATE}&jobName=DAILY_0000`;
  for (let waited = 0; waited < WAIT_SECONDS; waited += 5) {
    sleep(5);
    const res = http.get(`${BASE_URL}/v1/admin/batch-executions?${query}`, { headers });
    const items = res.status === 200 ? res.json('data.items') || [] : [];
    const status = items.length > 0 ? items[0].status : 'UNKNOWN';
    if (status === 'SUCCESS') {
      console.log(`00:00 묶음이 끝났다(${waited + 5}초)`);
      return;
    }
    if (status === 'FAILED') {
      fail(`00:00 묶음이 실패했다: ${items[0].failureReason}`);
    }
  }
  fail(`${WAIT_SECONDS}초 안에 00:00 묶음이 끝나지 않았다`);
}
