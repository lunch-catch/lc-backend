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
| 이벤트 로그 기반 캠페인 성과 | 요청 시 캠페인별 이벤트 집계 |
| 점주 캠페인 리포트 | 점주용 리포트와 변경 이력 조회 |

A-11 피드 요청 제한 초과 집계는 구현하지 않는다. 요청 제한은 adserving의 운영 지표로 관측하고 회원별 429 건수는 analytics 테이블에 저장하지 않는다.

A-04 카테고리·지역별 분해와 A-08 점주별 상위 소진도 구현하지 않는다. 요구사항의 선택 데이터이고, 가게에 지역 값이 없어 지역 분해를 만들 수 없다.

### 현재 구현과 후속 설계의 경계

기준은 이 PR에 반영한 `develop`과 실제 컨트롤러·집계 단계다. 아래의 후속 설계는 엔드포인트가 이미 제공된다는 뜻이 아니다.

| 기능 | 확인한 구현 상태 |
|---|---|
| A-01, A-02, A-07 | 조회와 일별 집계 단계가 구현돼 있다 |
| A-05, A-06, A-13 | 재계산·실행 조회·이벤트 수집이 구현돼 있다 |
| 점주 일별 리포트 | `OwnerReportQueryService`가 저장된 행을 조회한다. 채우는 `CampaignReportStep`은 없다 |
| 이벤트 로그 기반 캠페인 성과 | `CampaignFunnelService`가 요청 시 자기 로그를 집계한다. A-03과 별개다 |
| A-03 | 전용 테이블의 집계·조회는 PR #461 브랜치에 있다. 아직 `develop`에는 없다 |
| A-09, A-10, A-12, A-14, 상세 캠페인 리포트와 변경 이력 | 저장 구조와 설계는 있으나 해당 조회·집계 구현은 없다 |
| 이벤트 로그 13개월 삭제 | 설계만 있다. `EventLogRetentionJob` 구현은 없다 |

## 2. 공통 원칙

- 배치 지표 조회 API는 analytics의 집계 테이블만 읽는다. 이벤트 로그 기반 캠페인 성과 조회는 `event`에서 자기 로그를 읽는다.
- 일별 집계 배치만 원천 도메인의 집계 조회 contract를 부른다.
- 배치 지표 조회는 다른 업무 도메인을 부르지 않는다. 리포트 소유 확인도 리포트 행의 `owner_id`로 한다(#394). 이벤트 로그 기반 캠페인 성과만 `CampaignQueryService.findAllById`로 소유권을 확인한다(#509).
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
| A-02 가게 지표 | `daily_store_analytics` | `StoreStatsQueryService`, `CampaignStatsQueryService` |
| A-03 퍼널 | 전용 `daily_funnel_analytics`(PR #461, 미병합) | `AdservingStatsQueryService`, `CouponStatsQueryService`, analytics의 `event_log` |
| A-05 재집계 | 운영 실행 기록, `aggregation_pending_date` | `DailyAggregationRunner` |
| A-06 실행 이력 | 운영 `batch_execution_log` | `BatchExecutionQueryService` |
| A-07 매출 | `daily_settlement_analytics` | `BillingStatsQueryService` |
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

신규 가게는 그날 최종 등록한 가게다. 누적 가게는 그날 말까지 최종 등록한 가게 수다. 폐업이나 점주 상태와 무관하게 최종 등록 이력은 누적에 남는다. 카테고리는 현재 값을 사용하며 과거 분류 이력은 두지 않는다.

### 퍼널

유효·무효 노출은 `AdservingStatsQueryService`, 발급·사용은 `CouponStatsQueryService.dailyCampaignStats`의 확정 건수를 사용한다. 찜은 아래 PR #461의 구현처럼 analytics의 `event_log`에서 센다. 확정 업무 건수와 유실 가능한 행동 로그를 같은 신뢰도로 취급하지 않는다.

A-03은 전용 `daily_funnel_analytics`에 영업일당 한 행으로 저장한다. 원본 설계의 전용 테이블 결정을 PR #461의 `FunnelMetricsStep`, `FunnelAnalyticsRepository`, `FunnelQueryService`와 대조했다. 이전의 캠페인 리포트 합산안은 채택하지 않는다. 캠페인을 확인할 수 없는 무효 노출도 플랫폼 합계에 들어가야 하고 캠페인 리포트 구현을 선행 조건으로 만들 필요가 없기 때문이다.

PR #461 구현은 노출을 광고 서빙 집계 조회에서, 발급·사용을 쿠폰 집계 조회에서 받는다. 찜은 원본의 광고 서빙 집계안과 달리 `event_log`의 `wish`를 센다. 광고 서빙의 `swipe_log`는 31일 보존이라 그 뒤 재집계하면 0으로 덮일 수 있기 때문이다. 이벤트 유실 때문에 찜 수가 확정 원본보다 적을 수 있으며 현재 찜 목록 개수와도 다르다.

`develop`에는 이 전용 테이블이 아직 없다. PR #461의 `V3__analytics_funnel.sql`은 이미 병합된 `V3__campaign_event_lookup_index.sql`과 버전이 겹친다. #461에서 새 마이그레이션 번호로 옮긴 뒤 통합 검증해야 한다. 이 문서 보완으로 스키마를 적용한 것은 아니다.

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

`daily_invalid_analytics`에는 (캠페인, 회원, 사유) 행만 저장한다. A-09의 필터와 사유별 합계는 조회할 때 `GROUP BY`로 합산한다. 플랫폼, 캠페인, 회원 합계 행은 두지 않는다. 합계 행을 섞어 더하면 이중으로 계산되기 때문이다.

집중 경고는 캠페인과 영업일 단위로 판정한다.

```text
validCount >= minimumImpressions
topMembersShare > concentrationShare
```

설계의 초기 설정은 상위 5명, 점유율 0.5, 최소 유효 노출 100건이며 analytics의 설정값으로 둔다. 현재 `develop`에는 경고 집계 단계와 이 설정 구현이 없다. 기간 조회는 하루라도 집중 경고가 있으면 `true`이고 `topMembersShare`는 기간 중 가장 높은 하루 값이다.

무효 비율은 `invalidCount / validCount`로 계산한다(요구사항 BR-054, 2026-10-19). `validCount`가 0이면 `null`이다. 비율은 1을 넘을 수 있다.

```text
invalidCount >= minimumInvalidCount
AND (validCount == 0 OR invalidCount / validCount > 0.20)
```

최소 무효 건수의 초기 설정은 100건이며 analytics의 설정값이다. 없는 캠페인 ID와 정상 만료가 만드는 소량의 무효로 경고가 쏟아지지 않게 하는 값이다. 유효가 0이고 무효가 있는 캠페인도 같은 경고로 띄우고 비율만 `null`이다. 기간 조회는 합계로 판정한다.

유효 100·무효 21이면 비율은 21%라 경고한다. `invalidCount / (validCount + invalidCount)`였다면 약 17.4%라 경고하지 않았다. 분모가 바뀌면 같은 데이터의 경고 여부가 바뀌므로 화면 계약의 최종 확인(R-25)은 남아 있다.

날짜별로 판정하므로 한 달에 걸쳐 조금씩 나눠 소진시키는 공격은 놓칠 수 있다. 기간 전체의 회원별 노출을 보관하지 않기 때문이다. 화면은 무효 비율 경고와 함께 본다.

## 8. 회원 사용 통계

`daily_member_usage`는 영업일과 회원별 찜 수, 사용 수, 확정 할인액을 저장한다. 사용 수와 확정 할인액은 유실 가능한 퍼널 이벤트가 아니라 `CouponStatsQueryService`가 반환한 쿠폰 확정 기록으로 계산한다. 찜 수는 analytics의 `event_log`에서 계산한다.

A-12 설계는 회원의 모든 일별 행을 합산한다. 원본의 2026-10-16 이후 결정은 `daily_member_usage`를 13개월 삭제 대상에서 제외하고 탈퇴 회원 행도 유지하는 안이다. 현재 집계·조회·탈퇴 연동 구현이 없으므로 삭제를 수행한다고 적지 않는다. 보존과 탈퇴 데이터 처리의 최종 합의는 구현 전에 확인해야 한다.

V2 테이블은 만들어져 있다. `CouponStatsQueryService.dailyMemberStats(LocalDate)`도 #469로 들어왔으므로 회원별 일별 사용 수와 확정 할인액을 받을 수 있다. 원본의 "쿠폰 계약이 없어 착수할 수 없다"는 설명은 현재와 다르다. `MemberUsageStep`과 A-12 조회는 여전히 후속 구현이다.

## 9. 캠페인 리포트

`CampaignReportStep`이 `campaign_daily_report`와 자식 테이블을 채운다. 컬럼별 원천은 아래와 같다.

| 값 | 원천 |
|---|---|
| 하루 예산, 단가, 과금액, 정정액 | `BillingStatsQueryService` |
| 유효, 무효, 미과금 노출과 사유, 슬롯, 시간대 | `AdservingStatsQueryService`와 `BillingStatsQueryService` |
| 찜 | `AdservingStatsQueryService` |
| 발급, 사용 | `CouponStatsQueryService` |
| 점주와 가게 | 원본은 배치에서 `CampaignQueryService.findAllById`로 읽어 리포트 행에 저장하도록 정했다. 아직 배치 구현은 없다 |

첫 집계가 끝나면 리포트는 `FINALIZED`다. 당일은 `AGGREGATING`으로 표시한다. 확정 뒤 공개 지표가 바뀌면 변경 전후 JSON과 사유를 `campaign_daily_report_history`에 남긴다.

| 사유 | 언제 |
|---|---|
| `LATE_EVENT` | 정기 재집계에서 늦게 확정된 원천이 반영됨 |
| `OVERCHARGE_REVERSAL` | 과거 과금의 양수 ADJUST가 반영됨 |
| `MANUAL_RECALCULATION` | 관리자가 수동 재집계를 실행함 |

상세 캠페인 리포트의 목표 계약은 리포트 행의 `owner_id`로 소유를 확인하는 것이다(#394). 기간에 집계 행이 있고 소유 점주가 다르면 `403`, 집계 행이 없으면 빈 `items`다. 이미 구현된 점주 일별 리포트는 요청에 캠페인 ID를 받지 않고 로그인한 점주의 모든 행만 읽는다. 이벤트 로그 기반 캠페인 성과는 별도 API이며 캠페인 contract로 소유권을 확인한다.

캠페인 contract에는 `findAllById`가 이미 있고 `CampaignInfo`가 점주·가게 ID를 반환한다. 원본도 리포트의 연결 정보를 위한 새 집계 contract는 만들지 않기로 했다. `CampaignStatsQueryService`에 없는 일괄 조회를 구현된 것처럼 전제하지 않는다. 배치에서 연결 정보를 읽는 허용 범위는 실제 `CampaignReportStep`을 추가할 때 아키텍처 규칙과 함께 확인한다.

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

V2는 `campaign_daily_report.owner_id`와 `store_id`를 nullable로 추가했다. 기존 행의 백필과 `NOT NULL` 전환은 아직 구현되지 않았다. 점주 일별 조회는 로그인한 점주의 `owner_id`로 거르므로 소유 점주가 없는 행을 반환하지 않는다.

## 11. 성능

- 배치 지표 조회 API는 다른 도메인을 호출하지 않고 미리 계산한 집계 테이블을 읽는다.
- 관리자 목록은 요청당 최대 100건이고 캠페인 ID 일괄 조회는 최대 100개다.
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
| 회원 사용 통계의 보존·탈퇴 처리 | 쿠폰의 `dailyMemberStats` 계약은 있다. 일별 집계와 보존·탈퇴 정책의 최종 확인은 남았다 | A-12 구현 |
| 집중 경고의 기간 의미 | 하루라도 경고이고 최고 하루 점유율을 반환한다. 화면 확인이 남았다 | 화면 계약 확정 |
| 리포트 집계와 연결 정보 | 기존 `CampaignQueryService.findAllById`를 쓰는 원본 설계와 배치 허용 범위를 확인해야 한다 | `CampaignReportStep`, 상세 리포트 |
| 원래 영업일의 정정 반영 | `SettlementMetricsStep`은 정정 대상 날짜를 미처리 목록에 남긴다. 캠페인 리포트와 변경 이력을 쓰는 단계는 없다 | 리포트 정정 공개 |
| 실행 세대 확인 | ops의 이어받기는 있어도 일별 집계 실행기가 자기 실행 세대를 전달받거나 공개 시 잠그지 않는다 | 여러 서버의 안전한 공개 |
| A-03 마이그레이션 | PR #461과 develop의 V3 번호가 겹친다 | A-03 기능 병합 |

`CampaignStatsQueryService.activeCampaignCount`와 A-02의 `StoreMetricsStep`은 구현돼 있으므로 과거의 #354 대기 항목은 남기지 않는다. A-06의 시각은 #490이 KST 오프셋을 붙이도록 고쳤다.
