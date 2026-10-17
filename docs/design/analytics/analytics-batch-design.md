# analytics 배치 설계

analytics가 등록하는 일별 집계, 퍼널 이벤트 수신, 이벤트 로그 보존 작업을 정의한다. HTTP 계약은 [analytics API 명세](../../api-spec/analytics-api-spec.md)가 갖고 공통 구조는 [analytics 구조와 동작 설계](./analytics_구조와_동작_설계.md)가 갖는다.

## I-01. 일별 집계 배치

### 실행

| 항목 | 내용 |
|---|---|
| 실행 시각 | 01:00 KST `@Scheduled`. 00:00 묶음 밖에서 실행한다 |
| 작업명 | `DAILY_AGGREGATION` |
| 대상 날짜 | 전일과 `aggregation_pending_date`에 남은 날짜. 운영의 `rerun`이 제공되면 전전일도 포함한다 |
| 보존 범위 | 현재 날짜에서 13개월 안의 날짜만 집계한다 |
| 날짜 점유 | 실행 기록이 없으면 `claim`, FAILED면 `retry`, SUCCESS면 `rerun`, RUNNING이면 건너뛴다 |
| 수동 재집계 | A-05가 정기 실행과 같은 실행기를 부른다 |
| 실패 | 날짜를 미처리 목록에 남기고 실행 기록을 FAILED로 닫는다 |

Spring Batch는 사용하지 않는다. 각 단계는 평범한 서비스이고 `DailyAggregationRunner`가 순서대로 부른다. 실행 점유와 이어받기는 [운영 배치 설계](../ops/런치캐치_배치_운영.md)를 따른다.

### 모으기와 공개

하루 결과는 모으기와 공개로 나눈다.

1. 모으기는 트랜잭션 없이 원천 도메인의 집계 조회 contract와 `event_log`를 읽어 메모리에 결과를 만든다.
2. 일시적 실패만 트랜잭션 밖에서 기다린 뒤 하루 결과를 처음부터 다시 모은다.
3. 공개는 한 트랜잭션에서 실행 세대를 확인하고 analytics 테이블의 하루 결과를 교체한다.
4. 검증 단계가 등식을 확인한 뒤 성공하면 미처리 날짜를 지운다.

공개 트랜잭션은 외부 도메인을 호출하지 않는다. 임대를 잃은 서버가 늦게 결과를 공개하지 못하도록 트랜잭션 첫머리에서 실행 세대를 확인한다. 실행 세대 contract는 이슈 `#360`이 소유한다.

### 단계 구성

| 단계 | 산출 테이블 | 원천 |
|---|---|---|
| `SettlementMetricsStep` | `daily_settlement_analytics` | `BillingStatsQueryService` |
| `OwnerSpendStep` | `daily_owner_spend` | `BillingStatsQueryService` |
| `MemberMetricsStep` | `daily_member_analytics` | `MemberStatsQueryService`, analytics의 `event_log` |
| `FunnelMetricsStep` | `daily_funnel_analytics` | `AdservingStatsQueryService`, `CouponStatsQueryService`, analytics의 `event_log` |
| `StoreMetricsStep` | `daily_store_analytics` | `StoreStatsQueryService`, 캠페인과 점주 상태 집계 조회 |
| `SegmentMetricsStep` | `daily_segment_analytics` | `AdservingStatsQueryService`, `CouponStatsQueryService`, `StoreStatsQueryService`, `CampaignQueryService`, `BillingStatsQueryService` |
| `InvalidMetricsStep` | `daily_invalid_analytics` | `AdservingStatsQueryService` |
| `NotBilledMetricsStep` | `daily_campaign_not_billed_analytics` | `BillingStatsQueryService`, `AdservingStatsQueryService` |
| `FraudMetricsStep` | `daily_campaign_fraud_analytics` | `AdservingStatsQueryService` |
| `CampaignReportStep` | `campaign_daily_report`와 자식 4종 | `BillingStatsQueryService`, `AdservingStatsQueryService`, `CouponStatsQueryService`, `CampaignQueryService` |
| `MemberUsageStep` | `daily_member_usage` | `CouponStatsQueryService`, `MemberStatsQueryService`, analytics의 `event_log` |
| `ConsistencyCheckStep` | 지표와 구조화 로그 | 앞 단계가 모은 결과 |

원천 도메인의 집계 조회가 아직 없으면 해당 단계는 구현하지 않는다. analytics가 다른 도메인의 contract 타입이나 행 단위 원본을 먼저 만들지 않는다.

### 다른 도메인 읽기

- 다른 도메인은 그 도메인의 `contract`에 공개된 `{Domain}StatsQueryService`로만 읽는다.
- 원천 도메인이 자기 테이블을 `GROUP BY`하고 analytics에는 묶은 record만 반환한다.
- analytics는 캠페인 ID와 영업일을 키로 여러 원천의 결과를 메모리에서 합친다.
- 다른 도메인의 엔티티, 저장소, 테이블 이름을 analytics 코드에 두지 않는다.
- 도메인을 넘는 JPQL 조인과 네이티브 조인을 사용하지 않는다.
- analytics의 네이티브 쿼리는 analytics가 소유한 테이블만 읽고 쓴다.

### 날짜 귀속

다른 도메인의 값은 원천 도메인의 집계 조회가 아래 기준으로 묶어 반환한다.

| 값 | 영업일 기준 |
|---|---|
| RESERVE, RELEASE | 원장의 `business_date` |
| DEDUCT | 차감 판정의 `business_date` |
| CHARGE, REFUND | 기록 시각의 KST 날짜 |
| ADJUST | 정정 대상 영업일(`point_ledger.adjust_target_date`). 캠페인이 없는 조정과 그 컬럼을 두기 전에 기록된 행은 기록 시각의 KST 날짜 |
| 미소진 잔액 | 영업일 다음 날 00:00 이전에 실제 기록된 `CHARGE`, `REFUND`, `ADJUST`, `DEDUCT`의 누적 합. 점주 잔액에 예약 중 포인트를 더한 값과 같다 |
| 노출 | 서빙한 영업일 |
| 퍼널 이벤트 | `event_log.occurred_at`의 KST 날짜 |
| 쿠폰 발급, 사용, 만료, 확정 할인액 | 쿠폰 도메인이 정한 영업일 |
| 그날 말 상태 | 영업일 23:59:59.999999 기준 |

### billing 집계 조회의 메서드

billing은 2026-10-16에 `BillingStatsQueryService`를 공개했다(#352, #402). analytics 쪽 단계는 아직 구현 전이고 아래는 단계가 부를 메서드의 대응이다. 귀속 기준과 합계의 정의는 [billing 구조와 동작 설계](../billing/billing_구조와_동작_설계.md#집계-조회의-영업일-귀속)가 소유한다.

| 메서드 | 돌려주는 것 | 쓰는 단계 |
|---|---|---|
| `ledgerSums(영업일)` | 점주와 캠페인별 `CHARGE`, `RESERVE`, `DEDUCT`, `RELEASE`, `REFUND`와 양수·음수 `ADJUST` 합 | `SettlementMetricsStep`, `OwnerSpendStep`, `CampaignReportStep` |
| `billingCounts(영업일)` | 캠페인별 `BILLED`, `NOT_BILLED_BUDGET`, `NOT_BILLED_PAUSED` 건수 | `NotBilledMetricsStep`, `CampaignReportStep` |
| `dailyBudgets(영업일)` | 캠페인별 그날 적용 하루 예산과 단가 | `CampaignReportStep` |
| `dailyTotals(영업일)` | 그날 말 미소진 잔액, 원장 대조용 합계, 원장 불일치 건수 | 미소진 잔액은 `SettlementMetricsStep`(`unspent_balance`), 나머지 둘은 `ConsistencyCheckStep`의 검증. 불일치 건수는 A-07 응답에 없다 |
| `correctionTargetDates(기록 영업일)` | 그날 기록된 양수 `ADJUST`가 가리키는 정정 대상 영업일 | 재집계 대상 찾기 |

- 합계의 부호는 저장 부호 그대로다. `RESERVE`, `DEDUCT`, `REFUND`와 음수 `ADJUST`는 0 이하다. 소진액 계산은 `SpendFormula`가 한 곳에서 한다.
- `correctionTargetDates`는 그날 집계 뒤에 과거 영업일을 다시 집계할 날짜를 찾는 입력이다. 반환된 날짜를 `aggregation_pending_date`에 `ADJUSTED`로 남겨 다음 실행이 다시 집계하게 한다. 기록 영업일과 같은 날짜가 섞여 올 수 있어 같은 날은 거른다.
- 정정은 영업일 합계에서는 정정 대상 영업일에, 미소진 잔액과 `dailyTotals.ledgerSum`에서는 기록한 날에 들어간다. 그래서 기록한 날의 `ledgerSum`과 대상 영업일의 `ledgerSums`는 서로 다른 몫이다.
- 끝나지 않은 영업일을 넘겨도 billing은 막지 않고 그 시점까지 기록된 값을 돌려준다.

### 공개 규칙

- `daily_*` 테이블은 영업일 행을 지우고 새 결과를 넣는다.
- `campaign_daily_report`는 ID를 유지하며 갱신한다. 자식 3개 테이블은 지우고 다시 넣는다.
- 같은 날짜를 다시 실행해도 결과가 같아야 한다.
- 공개 트랜잭션 동안 조회가 빈 결과를 보지 않아야 한다.
- 소진액은 `DEDUCT 합 - 캠페인에 연결된 양수 ADJUST 합`이고 음수를 허용한다.
- 플랫폼 소진액은 `캠페인 소진액 합 - 캠페인 없는 양수 ADJUST 합`과 같아야 한다.
- 등식이 어긋나면 지표와 구조화 로그를 남기고 값을 자동으로 고치지 않는다.

## I-02. 퍼널 이벤트 수신

서버에서 발생하는 퍼널 이벤트는 발생 도메인의 `contract`가 소유한다. analytics는 커밋 뒤 비동기로 받아 `event_log`에 기록한다.

| 이벤트 | 발행 도메인 | `event_type` |
|---|---|---|
| `ImpressionServedEvent` | adserving | `impression` |
| `SwipedEvent` | adserving | `wish`, `pass` |
| `CouponIssueOpenedEvent` | coupon | `issue_open` |
| `CouponIssuedEvent` | coupon | `issue` |
| `CouponRedeemedEvent` | coupon | `redeem` |
| `CouponExpiredEvent` | coupon | `expire` |
| `NotificationSentEvent` | notification | `notification_sent` |

화면 조회 이벤트 `poster_open`, `map_view`, `wishlist_view`는 A-13 HTTP 요청으로 받는다.

- 리스너는 `@TransactionalEventListener(AFTER_COMMIT)`과 전용 비동기 실행기를 사용한다.
- 퍼널 기록은 유실을 허용한다. 돈과 맞아야 하는 건수는 원천 도메인의 집계 조회로 센다.
- 같은 `event_id`는 한 건만 저장하고 중복은 오류 없이 무시한다.
- `issue_open`은 회원이 없는 시스템 이벤트라 `member_id`가 없다.
- `coupon_event_id`는 발급 회차를 가리킨다. `member_coupon_id`는 발급된 쿠폰을 가리킨다.
- `issue_open`은 `coupon_event_id`만 필수다. `issue`는 두 쿠폰 식별자가 모두 필수다.
- `redeem`, `expire`는 `member_coupon_id`가 필수이고 `coupon_event_id`는 선택이다.
- `wish_id`는 `wish`에 필수이고 `wishlist_view`에는 선택이다. 다른 유형에는 둘 수 없다.
- `occurred_at`이 수신 시각보다 미래면 수신 시각으로 보정한다.
- 정정 이벤트 유형은 두지 않는다.

## I-03. 이벤트 로그 보존

`event_log`는 파티셔닝하지 않는다. MySQL은 파티션 키를 모든 UNIQUE 키에 포함하도록 요구하므로 `occurred_at`으로 파티셔닝하면 `event_id` 단독 멱등성을 DB 제약으로 지킬 수 없다.

`EventLogRetentionJob`은 매일 04:00 KST에 실행하고 13개월보다 오래된 행을 1000건씩 반복 삭제한다. 작업명은 `EVENT_LOG_RETENTION`이다. `(occurred_at)` 인덱스로 삭제 범위를 찾는다. `daily_member_usage`는 원본 로그가 아니라 누적 계약을 유지하기 위한 집계라 보존 삭제 대상이 아니다.
