# 도메인 설계서

도메인 안의 설계와 그 도메인 API 의 설계를 담는다. 이 폴더가 소유하는 것과 소유하지 않는 것이 갈린다.

| 구분 | 소유 |
|---|---|
| 이 폴더 | 모듈과 객체, 저장 구조와 동작, 의존하는 것이 멈췄을 때의 처리, 그 API 를 그렇게 만든 이유 |
| [`docs/api-spec/`](../api-spec/api-spec.md) | 밖에 약속한 계약. 경로, 인증, 요청과 응답의 모양, 상태 코드 |
| [`docs/architecture/`](../architecture/런치캐치_도메인_구조와_의존성_설계.md) | 도메인의 경계를 어디에 긋고 어느 쪽으로 의존하게 할지 |

쓸 때 기준이 되는 가이드가 둘이다.

| 가이드 | 정하는 것 |
|---|---|
| [구조와 동작 설계 가이드](./구조와_동작_설계_가이드.md) | 모듈과 객체, 저장 구조와 동작을 어떤 모양으로 쓰는지 |
| [장애 처리 설계 가이드](./장애_처리_설계_가이드.md) | 의존하는 것이 멈췄을 때 막을지 통과시킬지, 벗어날 때 무엇을 적는지 |

## 도메인별 문서

| 도메인 | 구조와 동작 | 장애 처리 | API 설계 | 그 밖 |
|---|---|---|---|---|
| adserving | [구조와 동작](./adserving/adserving_구조와_동작_설계.md) | [장애 처리](./adserving/adserving_장애_처리_설계.md) | [feed](./adserving/feed-api-design.md) | - |
| billing | [구조와 동작](./billing/billing_구조와_동작_설계.md) | [장애 처리](./billing/billing_장애_처리_설계.md) | [billing](./billing/billing-api-design.md) | [배치](./billing/billing-batch-design.md) |
| coupon | [구조와 동작](./coupon/coupon_구조와_동작_설계.md) | [장애 처리](./coupon/coupon_장애_처리_설계.md) | [coupon](./coupon/coupon-api-design.md) | 발급 [v1](./coupon/coupon-issuance-v1-design.md) [v2](./coupon/coupon-issuance-v2-design.md) [v3](./coupon/coupon-issuance-v3-design.md), [배치](./coupon/coupon-batch-design.md), [통계 조회 계약](./coupon/coupon-stats-query-contract.md) |
| campaign | [구조와 동작](./campaign/campaign_구조와_동작_설계.md) | [장애 처리](./campaign/campaign_장애_처리_설계.md) | [campaign](./campaign/campaign-api-design.md) | 포스터 [설계](./campaign/poster_구조와_동작_설계.md) [API](./campaign/poster-api-design.md), [템플릿](./campaign/template_구조와_동작_설계.md) |
| store | [구조와 동작](./store/store_구조와_동작_설계.md) | [장애 처리](./store/store_장애_처리_설계.md) | [store](./store/store-api-design.md) | [조회 계약](./store/store-query-contract.md), [입점 진행](./store/store-registration-progress.md) |
| member | [구조와 동작](./member/member_구조와_동작_설계.md) | [장애 처리](./member/member_장애_처리_설계.md) | [member](./member/member-api-design.md) | - |
| owner | [구조와 동작](./owner/owner_구조와_동작_설계.md) | [장애 처리](./owner/owner_장애_처리_설계.md) | [owner](./owner/owner-api-design.md) | [세션](./owner/owner-sessions.md), [상태 캐시](./owner/owner-status-cache.md) |
| admin | [구조와 동작](./admin/admin_구조와_동작_설계.md) | [장애 처리](./admin/admin_장애_처리_설계.md) | [admin](./admin/admin-api-design.md) | - |
| analytics | [구조와 동작](./analytics/analytics_구조와_동작_설계.md) | [장애 처리](./analytics/analytics_장애_처리_설계.md) | [analytics](./analytics/analytics-api-design.md) | [배치](./analytics/analytics-batch-design.md) |
| auth | [구조와 동작](./auth/auth_구조와_동작_설계.md) | [장애 처리](./auth/auth_장애_처리_설계.md) | [auth](./auth/auth-api-design.md) | - |
| ops | [구조와 동작](./ops/ops_구조와_동작_설계.md) | [장애 처리](./ops/ops_장애_처리_설계.md) | [실행 이력](./ops/batch-execution-history-api-design.md), [재실행](./ops/batch-execution-retry-api-design.md) | [배치 운영](./ops/런치캐치_배치_운영.md) |

`global` 은 도메인이 아니라 기술 공통이라 이 폴더에 문서를 두지 않는다. 예외와 응답 포맷의 규약은 [`docs/code-convention/`](../code-convention) 과 [API 명세](../api-spec/api-spec.md)가 갖는다.

## 도메인을 가로지르는 비교

한 도메인에 속하지 않고 여러 도메인이 같은 선택을 해야 할 때 그 비교를 폴더 바로 아래에 둔다.

| 문서 | 다루는 것 |
|---|---|
| [요청 중 쓰기의 지연과 유실 허용 범위 비교](./요청_중_쓰기의_지연과_유실_허용_범위_비교.md) | 요청 처리 중에 쓰는 기록마다 얼마나 늦어도 되고 잃어도 되는지 |

## 새 도메인을 더할 때

1. `docs/design/{도메인}/` 을 만든다.
2. 위 가이드 둘을 기준으로 `{도메인}_구조와_동작_설계.md` 와 `{도메인}_장애_처리_설계.md` 를 쓴다.
3. 그 도메인의 API 설계를 `{문서명}-api-design.md` 로 쓴다. 계약은 여기가 아니라 `docs/api-spec/` 에 쓴다.
4. 이 색인의 표에 줄을 더한다.
