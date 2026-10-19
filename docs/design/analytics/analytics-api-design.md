# analytics API 설계

[analytics API 명세](../../api-spec/analytics-api-spec.md)의 응답을 어떻게 만들고 저장하는지 정의한다. 도메인 공통 구조는 [analytics 구조와 동작 설계](./analytics_구조와_동작_설계.md)가 갖고 배치 실행은 [analytics 배치 설계](./analytics-batch-design.md)가 갖는다.

## 1. 적용 범위

| 묶음 | API |
|---|---|
| 관리자 대시보드 | A-01부터 A-06 |
| 매출 | A-07, A-08 |
| 부정 관리 | A-09, A-10 |
| 관리자 캠페인 리포트 | A-14 |
| 회원 사용 통계 | A-12 |
| 이벤트 수집 | A-13 |
| 이벤트 로그 기반 캠페인 성과 | 요청 시 캠페인별 이벤트 집계 |
| 점주 캠페인 리포트 | 점주용 리포트와 변경 이력 조회 |

A-11 피드 요청 제한 초과 집계는 구현하지 않는다. 요청 제한은 adserving의 운영 지표로 관측하고 회원별 429 건수는 analytics 테이블에 저장하지 않는다.

## 2. 공통 원칙

- 배치 지표 조회 API는 analytics의 집계 테이블만 읽는다. 이벤트 로그 기반 캠페인 성과 조회는 `event`에서 자기 로그를 읽는다.
- 일별 집계 배치만 원천 도메인의 집계 조회 contract를 부른다.
- 리포트 소유 확인은 `CampaignQueryService.findAllById`를 요청마다 호출한다. 이 예외는 이슈 `#351`이 아키텍처 규칙과 테스트에 반영한다.
- 확정 리포트의 노출, 발급, 사용 건수는 퍼널 이벤트 로그가 아니라 원천 도메인의 집계 조회에서 온다.
- `event_log`는 활성 회원과 퍼널 분석에 사용한다. 원천 업무의 확정 건수와 일치한다고 보장하지 않는다.
- 모든 영업일과 시계열 버킷은 `Asia/Seoul` 기준이다.

## 3. 시계열 규약

이 절은 배치 지표 조회에 적용한다. 이벤트 로그 기반 캠페인 성과의 요청과 계산 규약은 [API 명세](../../api-spec/analytics-api-spec.md#이벤트-로그-기반-캠페인-성과)를 따른다.

| `period` | 최대 범위 | 버킷 |
|---|---|---|
| `DAILY` | 92일 | 영업일 |
| `WEEKLY` | 53주 | 월요일부터 일요일 |
| `MONTHLY` | 13개월 | 달력 월 |

`from`이 `to`보다 늦거나 최대 범위를 넘으면 `ANALYTICS-001`이다. 결과가 없는 버킷도 응답에 포함하고 값은 0으로 채운다. 비율의 분모가 0이면 `null`이다.

누적 값은 버킷 마지막 날 값을 쓰고 건수와 금액은 합산한다. 일평균 값은 일별 값을 더한 뒤 포함한 영업일 수로 나눈다. 비율과 단가는 합산된 분자와 분모로 다시 계산한다.

시계열 항목의 상태는 `BatchExecutionQueryService`가 반환한 `DAILY_AGGREGATION` 실행 기록으로 만든다. 성공한 날짜는 `FINAL`, 아직 확정되지 않은 날짜는 `IN_PROGRESS`다. `aggregatedAt`은 마지막 성공 시각이다.

## 4. API와 저장 원천

| API | analytics 저장 원천 | 배치 원천 |
|---|---|---|
| A-01 회원 지표 | `daily_member_analytics` | `MemberStatsQueryService`, `event_log` |
| A-02 가게 지표 | `daily_store_analytics` | `StoreStatsQueryService`, 캠페인과 점주 상태 집계 조회 |
| A-03 퍼널 | `daily_funnel_analytics` | `AdservingStatsQueryService`, `CouponStatsQueryService`, `event_log` |
| A-04 세그먼트 | `daily_segment_analytics` | 광고서빙, 쿠폰, 가게, 캠페인, 정산 집계 조회 |
| A-05 재집계 | 운영 실행 기록, `aggregation_pending_date` | `DailyAggregationRunner` |
| A-06 실행 이력 | 운영 `batch_execution_log` | `BatchExecutionQueryService` |
| A-07 매출 | `daily_settlement_analytics` | `BillingStatsQueryService` |
| A-08 점주별 소진 | `daily_owner_spend` | `BillingStatsQueryService` |
| A-09 무효 노출 | `daily_invalid_analytics` | `AdservingStatsQueryService` |
| A-10 캠페인 경고 | `daily_campaign_fraud_analytics` | `AdservingStatsQueryService` |
| A-12 회원 사용 통계 | `daily_member_usage` | `CouponStatsQueryService`, `MemberStatsQueryService`, `event_log` |
| A-13 이벤트 수집 | `event_log` | HTTP 요청 |
| 이벤트 로그 기반 캠페인 성과 | `event_log` | 배치 없이 요청 시 조회 |
| A-14 관리자 캠페인 리포트 | `campaign_daily_report` | 캠페인 리포트 집계 |
| 점주 캠페인 리포트 | `campaign_daily_report`와 자식 4종 | 캠페인 리포트 집계 |

## 5. 대시보드

### 점주 일별 리포트 조회

[점주 일별 리포트 API 명세](../../api-spec/analytics-api-spec.md#점주-일별-리포트)의 조회는 `OwnerReportQueryService`가 맡는다. `OwnerReportQueryRepository`는 `campaign_daily_report`의 `(owner_id, business_date)` 인덱스로 로그인한 점주의 기간 내 행을 읽는다. 다른 도메인은 호출하지 않는다.

소유 확인은 [아키텍처 설계 2.2](../../architecture/런치캐치_도메인_구조와_의존성_설계.md#22-그래프)에 따라 리포트에 저장된 `owner_id`로 한다. `owner_id`가 없는 기존 행은 반환하지 않는다.

조회는 저장된 일별 지표를 그대로 반환하고 응답 항목의 소진액을 합산한다. 요청마다 집계하거나 원천 데이터로 보완하지 않는다.

이슈 #492는 조회 API만 구현한다. `CampaignReportStep`은 아직 구현되지 않았으므로 배치가 리포트를 채우는 작업은 별도로 필요하다.

### 회원 지표

신규, 탈퇴, 누적 회원은 `MemberStatsQueryService`가 영업일 기준으로 계산한다. 활성 회원은 그날 `event_log`에 기록된 회원의 중복을 제거해 센다. 회원 정지 기능은 현재 구현 범위가 아니므로 `suspendedCount`는 0이다.

### 가게 지표

신규 가게는 그날 최종 등록한 가게다. 누적 가게는 그날 말까지 최종 등록한 가게 수다. 폐업이나 점주 상태와 무관하게 최종 등록 이력은 누적에 남는다. 카테고리와 지역은 현재 값을 사용하며 과거 분류 이력은 두지 않는다.

### 퍼널과 세그먼트

유효 노출과 무효 노출은 `AdservingStatsQueryService`에서 받는다. 발급과 사용은 돈과 맞아야 하는 건수라 쿠폰 집계 조회에서 받는다. 찜은 분석 자기 `event_log`의 `wish` 행 수로 센다. 광고 서빙의 `wishCount`는 31일만 보관하는 `swipe_log`가 원천이라 31일이 지난 날짜를 다시 집계하면 저장된 찜 수를 0으로 덮으므로 쓰지 않는다. `event_log`는 13개월 보존이라 재집계 한계와 같다. 이벤트가 유실을 허용해 아주 조금 낮을 수 있으나 찜 수는 돈과 맞아야 하는 값이 아니다.

`map_view`와 `wishlist_view`는 캠페인과 가게가 없는 화면 조회 이벤트라 카테고리와 지역 세그먼트에 넣지 않는다. 캠페인에서 가게로 연결한 뒤 `StoreStatsQueryService`가 반환한 카테고리와 지역으로 묶는다.

## 6. 매출과 소진액

`SpendFormula`는 아래 계산을 한 곳에서 제공한다.

```text
캠페인 소진액 = DEDUCT 합 - 캠페인에 연결된 양수 ADJUST 합
플랫폼 소진액 = 캠페인 소진액 합 - 캠페인 없는 양수 ADJUST 합
```

소진액은 정정액이 차감액보다 크면 음수가 될 수 있다. 0으로 자르지 않는다. 매출, 점주별 소진, 캠페인 리포트가 같은 계산을 사용한다.

`unspentBalance`는 기간 마지막 날의 미소진 잔액이다. 충전, 소진, 조정, 환불은 기간 합계다. 예약과 해제는 API 응답에 포함하지 않고 정합성 검증에만 사용한다.

A-08은 기간 소진액이 0 이하인 점주를 제외한다. `spentAmount` 내림차순과 `ownerId` 오름차순으로 정렬하고 최대 50건을 반환한다. 상호는 점주 목록 API가 붙인다.

## 7. 부정 관리

무효 노출 사유는 `EXPIRED_OR_UNKNOWN`, `NOT_OWNER` 두 가지다. 미과금 노출은 무효 노출이 아니라 유효 노출 안의 과금 상태다.

집중 경고는 캠페인과 영업일 단위로 판정한다.

```text
validCount >= minimumImpressions
topMembersShare > concentrationShare
```

초기 설정은 상위 5명, 점유율 0.5, 최소 유효 노출 100건이다. 운영 설정 contract가 제공되기 전에는 analytics 설정값을 사용한다. 기간 조회는 하루라도 집중 경고가 있으면 `true`이고 `topMembersShare`는 기간 중 가장 높은 하루 값이다.

무효 비율은 `invalidCount / (validCount + invalidCount)`로 계산한다. 요구사항의 다른 분모 정의는 R-23 결정 전까지 API 명세의 계산을 따른다.

## 8. 회원 사용 통계

`daily_member_usage`는 영업일과 회원별 찜 수, 사용 수, 확정 할인액을 저장한다. 사용 수와 확정 할인액은 유실 가능한 퍼널 이벤트가 아니라 `CouponStatsQueryService`가 반환한 쿠폰 확정 기록으로 계산한다. 찜 수는 analytics의 `event_log`에서 계산한다.

A-12는 현재 회원의 모든 일별 행을 합산한다. 원본 로그의 13개월 보존과 무관하게 누적 계약을 유지하므로 `daily_member_usage`는 보존 삭제하지 않는다. 탈퇴 회원은 모든 날짜의 행을 지우고 과거 날짜를 다시 집계할 때도 새로 만들지 않는다.

이 방식은 R-18의 팀 최종 확인이 남아 있다. 확인 전에도 V2 테이블은 만들지만 A-12 집계와 조회 구현은 후속 단위에서 진행한다.

## 9. 캠페인 리포트

`CampaignReportStep`이 `campaign_daily_report`와 자식 테이블을 채운다. 컬럼별 원천은 아래와 같다.

| 값 | 원천 |
|---|---|
| 하루 예산, 단가, 과금액, 정정액 | `BillingStatsQueryService` |
| 유효, 무효, 미과금 노출과 사유, 슬롯, 시간대 | `AdservingStatsQueryService`와 `BillingStatsQueryService` |
| 찜 | `AdservingStatsQueryService` |
| 발급, 사용 | `CouponStatsQueryService` |
| 점주와 가게 | `CampaignQueryService.findAllById` |

첫 집계가 끝나면 리포트는 `FINALIZED`다. 당일은 `AGGREGATING`으로 표시한다. 확정 뒤 공개 지표가 바뀌면 변경 전후 JSON과 사유를 `campaign_daily_report_history`에 남긴다.

| 사유 | 언제 |
|---|---|
| `LATE_EVENT` | 정기 재집계에서 늦게 확정된 원천이 반영됨 |
| `OVERCHARGE_REVERSAL` | 과거 과금의 양수 ADJUST가 반영됨 |
| `MANUAL_RECALCULATION` | 관리자가 수동 재집계를 실행함 |

점주 리포트 조회는 `CampaignQueryService.findAllById`로 현재 소유자를 확인한다. 본인 캠페인인데 기간에 집계 행이 없으면 빈 `items`를 반환한다. 남의 캠페인이거나 없는 캠페인은 존재 여부와 관계없이 `403`이다.

주와 월 응답은 건수와 금액을 합산한다. `budgetSpendRate`와 `costPerVisit`은 합산 값으로 다시 계산한다. `impressionUnitCost`는 주와 월 응답에서 `null`이다. 기간 안 마지막 확정 시각을 사용하고 하루라도 변경 이력이 있으면 변경됨으로 표시한다.

## 10. V2 저장 구조

| 변경 | 규칙 |
|---|---|
| `event_log.campaign_id`, `store_id` nullable | `map_view`, `wishlist_view`, `issue_open` 외 유형은 둘 다 필수 |
| `event_log.member_id` nullable | `issue_open`만 회원 없이 저장 가능 |
| 쿠폰 식별자 분리 | `coupon_event_id`, `member_coupon_id`를 유형별 CHECK로 제한 |
| `daily_settlement_analytics.positive_adjust_amount` | 소진액 계산에 사용하는 양수 ADJUST 합 |
| 소진액 CHECK 변경 | `daily_owner_spend`, `daily_segment_analytics`의 소진액에 음수를 허용 |
| 캠페인 리포트 차원 | `owner_id`, `store_id`와 `(owner_id, business_date)` 인덱스 추가 |
| `daily_campaign_fraud_analytics` | 캠페인과 영업일별 집중 경고 원천 |
| `daily_member_usage` | 회원과 영업일별 사용 통계 |
| `aggregation_pending_date` | 성공할 때까지 다시 처리할 영업일과 사유 |

`aggregation_pending_date.reason`은 `FAILED`, `SOURCE_NOT_READY`, `RERUN_UNAVAILABLE`, `ADJUSTED`만 허용한다.

`issue_id`는 새 쿠폰 식별자의 양쪽 쓰기와 읽기 전환이 끝날 때까지 유지한다. 기존 V1 행과 새 식별자를 사용하는 행을 함께 허용하고 후속 마이그레이션에서 제거한다.

기존 `campaign_daily_report` 행에는 점주와 가게 정보가 없다. V2는 `owner_id`와 `store_id`를 nullable로 추가한다. 캠페인 조회 계약으로 기존 행을 백필한 뒤 후속 마이그레이션에서 `NOT NULL`로 바꾼다.

## 11. 성능

- 배치 지표 조회 API는 다른 도메인을 호출하지 않고 미리 계산한 집계 테이블을 읽는다.
- 관리자 목록은 요청당 최대 100건이고 캠페인 ID 일괄 조회는 최대 100개다.
- A-08은 최대 50건이다.
- 배치는 원천 도메인에서 하루치로 묶은 결과만 받아 메모리에서 합친다.
- 공개 트랜잭션은 외부 호출 없이 analytics 테이블을 교체하는 시간만 유지한다.

### 이벤트 로그 기반 캠페인 성과 (#509)

`CampaignFunnelController`는 인증한 점주 ID와 조회 조건을 `CampaignFunnelService`에 전달한다. 서비스는 읽기 전용 트랜잭션에서 `CampaignQueryService.findAllById`로 소유권을 확인한다. `EventLogRepository.countCampaignEvents`가 자기 `event_log`를 조건부 COUNT 한 번으로 읽고 서비스가 비율을 계산한다.

발생 시각은 한국 날짜의 시작 이상, 종료일 다음 날 시작 미만으로 제한한다. `(campaign_id, occurred_at, event_type)` 인덱스는 캠페인과 시각 범위를 찾으며 집계에 필요한 유형도 포함한다. 새 집계 테이블과 배치 작업은 만들지 않는다.

이 API는 로그 애그리거트를 소유한 `analytics.event`에 둔다. 기존 `analytics.query`의 배치 지표 조회 제약은 유지한다. 도메인 경계와 소유권 조회의 예외는 [아키텍처 설계](../../architecture/런치캐치_도메인_구조와_의존성_설계.md#21-의존-규칙)가 소유한다.

`AnalyticsSecurityConfig`가 이 경로의 OWNER 역할을 검사한다. `CampaignSecurityConfig`의 넓은 캠페인 경로에서는 이 경로만 제외해 보안 체인의 등록 순서에 의존하지 않는다.

## 12. 아직 정하지 못한 것

| 항목 | 현재 기준 | 막는 작업 |
|---|---|---|
| R-18 회원 사용 통계 최종 소유 | analytics의 일별 집계를 제안한다 | A-12 구현 |
| R-23 무효 비율 분모 | API 명세의 전체 노출 분모를 임시 기준으로 쓴다 | A-10 확정 |
| R-25 집중 경고의 기간 의미 | 하루라도 경고이고 최고 하루 점유율을 반환한다 | 화면 계약 확정 |
| 이슈 `#351` | 캠페인 contract 예외와 회원, 가게, 점주 간선을 반영한다 | 점주 리포트 조회와 후속 집계 |
| 이슈 `#354` | 과거 영업일 말 ACTIVE 캠페인 수 제공 여부 | A-02 |
| 이슈 `#360` | 실행 세대 확인 contract | 안전한 배치 결과 공개 |
