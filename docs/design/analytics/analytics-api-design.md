# analytics API 설계

[analytics API 명세](../../api-spec/analytics-api-spec.md)의 응답을 어떻게 만들고 저장하는지 정의한다. 도메인 공통 구조는 [analytics 구조와 동작 설계](./analytics_구조와_동작_설계.md)가 갖고 배치 실행은 [analytics 배치 설계](./analytics-batch-design.md)가 갖는다.

## 1. 적용 범위

| 묶음 | API |
|---|---|
| 관리자 대시보드 | A-01부터 A-03, A-05, A-06 |
| 매출 | A-07 |
| 부정 관리 | A-09, A-10 |
| 관리자 캠페인 리포트 | A-14 |
| 회원 사용 통계 | A-12 |
| 이벤트 수집 | A-13 |
| 점주 캠페인 리포트 | 점주용 리포트와 변경 이력 조회 |

A-11 피드 요청 제한 초과 집계는 구현하지 않는다. 요청 제한은 adserving의 운영 지표로 관측하고 회원별 429 건수는 analytics 테이블에 저장하지 않는다.

A-04 카테고리·지역별 분해와 A-08 점주별 상위 소진도 구현하지 않는다. 요구사항의 선택 데이터이고, 가게에 지역 값이 없어 지역 분해를 만들 수 없다.

## 2. 공통 원칙

- 조회 API는 analytics의 집계 테이블만 읽는다.
- 일별 집계 배치만 원천 도메인의 집계 조회 contract를 부른다.
- 조회 API는 다른 도메인을 부르지 않는다. 리포트 소유 확인도 집계 배치가 리포트 행에 함께 저장한 소유 점주(`owner_id`)로 한다(#394).
- 돈과 맞아야 하는 노출, 발급, 사용 건수는 퍼널 이벤트 로그가 아니라 원천 도메인의 집계 조회에서 온다.
- `event_log`는 활성 회원과 퍼널 비율에만 사용한다.
- 모든 영업일과 시계열 버킷은 `Asia/Seoul` 기준이다.

## 3. 시계열 규약

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
| A-03 퍼널 | `campaign_daily_report`의 그날 합, `daily_invalid_analytics`의 플랫폼 합계 행 | 캠페인 리포트 집계, `AdservingStatsQueryService` |
| A-05 재집계 | 운영 실행 기록, `aggregation_pending_date` | `DailyAggregationRunner` |
| A-06 실행 이력 | 운영 `batch_execution_log` | `BatchExecutionQueryService` |
| A-07 매출 | `daily_settlement_analytics` | `BillingStatsQueryService` |
| A-09 무효 노출 | `daily_invalid_analytics` | `AdservingStatsQueryService` |
| A-10 캠페인 경고 | `daily_campaign_fraud_analytics` | `AdservingStatsQueryService` |
| A-12 회원 사용 통계 | `daily_member_usage` | `CouponStatsQueryService`, `MemberStatsQueryService`, `event_log` |
| A-13 이벤트 수집 | `event_log` | HTTP 요청 |
| A-14 관리자 캠페인 리포트 | `campaign_daily_report` | 캠페인 리포트 집계 |
| 점주 캠페인 리포트 | `campaign_daily_report`와 자식 4종 | 캠페인 리포트 집계 |

## 5. 대시보드

### 회원 지표

신규, 탈퇴, 누적 회원은 `MemberStatsQueryService`가 영업일 기준으로 계산한다. 활성 회원은 그날 `event_log`에 기록된 회원의 중복을 제거해 센다. 회원 정지 기능은 현재 구현 범위가 아니므로 `suspendedCount`는 0이다.

### 가게 지표

신규 가게는 그날 최종 등록한 가게다. 누적 가게는 그날 말까지 최종 등록한 가게 수다. 폐업이나 점주 상태와 무관하게 최종 등록 이력은 누적에 남는다. 카테고리는 현재 값을 사용하며 과거 분류 이력은 두지 않는다.

### 퍼널

유효 노출과 무효 노출은 `AdservingStatsQueryService`에서 받는다. 찜, 발급, 사용은 돈과 맞아야 하는 건수라 광고서빙과 쿠폰 집계 조회에서 받는다. 사용 수는 `CouponStatsQueryService.dailyCampaignStats`의 `usedCount`다. `event_log`는 화면 행동을 포함한 퍼널 비율 계산에만 쓴다.

A-03은 따로 저장하지 않고 이미 있는 두 테이블에서 계산한다. 유효 노출, 찜, 발급, 사용은 `campaign_daily_report`의 그날 행을 모두 더한다. 무효 노출은 `daily_invalid_analytics`의 플랫폼 합계 행(`campaign_id`가 NULL)에서 읽는다. `EXPIRED_OR_UNKNOWN`은 서빙 기록이 없어 캠페인을 모를 수 있어서 캠페인 리포트의 합으로는 빠지기 때문이다. 그래서 A-03은 `CampaignReportStep`과 `InvalidMetricsStep`이 먼저 있어야 한다.

처음에는 `daily_segment_analytics`를 원천으로 적었다. 이 테이블은 A-04(카테고리·지역별 분해)를 위해 `(category_code, region)`을 키로 만든 것이고 `region`이 NOT NULL이다. 가게에 지역 값을 두지 않기로 해서(#364) 쓸 수 없다. A-04용 `daily_segment_analytics`와 A-08용 `daily_owner_spend`는 채우는 단계가 없어 비어 있지만 폐기하지 않고 남겨 둔다.

## 6. 매출과 소진액

`SpendFormula`는 아래 계산을 한 곳에서 제공한다.

```text
캠페인 소진액 = DEDUCT 합 - 캠페인에 연결된 양수 ADJUST 합
플랫폼 소진액 = 캠페인 소진액 합 - 캠페인 없는 양수 ADJUST 합
```

소진액은 정정액이 차감액보다 크면 음수가 될 수 있다. 0으로 자르지 않는다. 매출과 캠페인 리포트가 같은 계산을 사용한다.

`unspentBalance`는 기간 마지막 날의 미소진 잔액이다. 충전, 소진, 조정, 환불은 기간 합계다. 예약과 해제는 API 응답에 포함하지 않고 정합성 검증에만 사용한다.

## 7. 부정 관리

무효 노출 사유는 `EXPIRED_OR_UNKNOWN`, `NOT_OWNER` 두 가지다. 미과금 노출은 무효 노출이 아니라 유효 노출 안의 과금 상태다.

집중 경고는 캠페인과 영업일 단위로 판정한다.

```text
validCount >= minimumImpressions
topMembersShare > concentrationShare
```

초기 설정은 상위 5명, 점유율 0.5, 최소 유효 노출 100건이고 analytics의 설정값으로 둔다. 배포로 바꿀 수 있다. 기간 조회는 하루라도 집중 경고가 있으면 `true`이고 `topMembersShare`는 기간 중 가장 높은 하루 값이다.

무효 비율은 `invalidCount / (validCount + invalidCount)`로 계산한다. 둘 다 0이면 `null`이고 경고하지 않는다.

날짜별로 판정하므로 한 달에 걸쳐 조금씩 나눠 소진시키는 공격은 놓칠 수 있다. 기간 전체의 회원별 노출을 보관하지 않기 때문이다. 화면은 무효 비율 경고와 함께 본다.

## 8. 회원 사용 통계

`daily_member_usage`는 영업일과 회원별 찜 수, 사용 수, 확정 할인액을 저장한다. 사용 수와 확정 할인액은 유실 가능한 퍼널 이벤트가 아니라 `CouponStatsQueryService`가 반환한 쿠폰 확정 기록으로 계산한다. 찜 수는 analytics의 `event_log`에서 계산한다.

A-12는 현재 회원의 모든 일별 행을 합산한다. 원본 로그의 13개월 보존과 무관하게 누적 계약을 유지하므로 `daily_member_usage`는 보존 삭제하지 않는다. 탈퇴 회원은 모든 날짜의 행을 지우고 과거 날짜를 다시 집계할 때도 새로 만들지 않는다.

이 방식은 팀 최종 확인이 남아 있다. 확인 전에도 V2 테이블은 만들지만 A-12 집계와 조회 구현은 후속 단위에서 진행한다. 지금 쿠폰 계약은 회원별 누적(`memberStats`)과 캠페인별 일 단위(`dailyCampaignStats`)만 주고 회원별 일 단위 집계는 없다. A-12를 만들려면 쿠폰에 회원별 일 단위 집계 조회를 요청해야 한다.

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

점주 리포트 조회는 리포트 행에 저장된 `owner_id`로 소유를 확인하고 다른 도메인을 부르지 않는다(#394). 기간에 집계 행이 있고 소유 점주가 다르면 `403`이다. 기간에 집계 행이 없으면 본인 캠페인인지와 관계없이 빈 `items`를 반환한다. 남의 캠페인이어도 같아서 존재 여부가 새지 않는다. 배치가 리포트 행에 저장하는 소유 점주와 가게는 캠페인의 `CampaignStatsQueryService`에 일괄 조회를 더해 받는다.

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

- 조회 API는 다른 도메인을 호출하지 않고 미리 계산한 집계 테이블을 읽는다.
- 관리자 목록은 요청당 최대 100건이고 캠페인 ID 일괄 조회는 최대 100개다.
- 배치는 원천 도메인에서 하루치로 묶은 결과만 받아 메모리에서 합친다.
- 공개 트랜잭션은 외부 호출 없이 analytics 테이블을 교체하는 시간만 유지한다.

## 12. 아직 정하지 못한 것

| 항목 | 현재 기준 | 막는 작업 |
|---|---|---|
| 회원 사용 통계 최종 소유 | analytics의 일별 집계를 제안한다. 쿠폰의 회원별 일 단위 집계 조회가 필요하다 | A-12 구현 |
| 집중 경고의 기간 의미 | 하루라도 경고이고 최고 하루 점유율을 반환한다. 화면 확인이 남았다 | 화면 계약 확정 |
| 리포트 행의 소유 점주 원천 | 캠페인의 `CampaignStatsQueryService`에 소유 점주 일괄 조회를 더해야 한다 | 점주 리포트 조회 |
| 이슈 `#354` | 과거 영업일 말 ACTIVE 캠페인 수 제공 여부 | A-02 |
| 이슈 `#360` | 실행 세대 확인 contract | 안전한 배치 결과 공개 |
