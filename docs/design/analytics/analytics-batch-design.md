# analytics 배치 설계

analytics가 등록하는 일별 집계, 퍼널 이벤트 수신, 이벤트 로그 보존 작업을 정의한다. HTTP 계약은 [analytics API 명세](../../api-spec/analytics-api-spec.md)가 갖고 공통 구조는 [analytics 구조와 동작 설계](./analytics_구조와_동작_설계.md)가 갖는다.

## I-01. 일별 집계 배치

### 실행

| 항목 | 내용 |
|---|---|
| 실행 시각 | 01:00 KST `@Scheduled`. 00:00 묶음 밖에서 실행한다 |
| 작업명 | `DAILY_AGGREGATION` |
| 대상 날짜 | 전일, 전전일과 `aggregation_pending_date`에 남은 날짜를 오래된 순서로 처리한다 |
| 보존 범위 | 현재 날짜에서 13개월 안의 날짜만 집계한다 |
| 날짜 점유 | 실행 기록이 없으면 `claim`, FAILED면 `retry`, SUCCESS면 `rerun`, RUNNING이면 건너뛴다 |
| 수동 재집계 | A-05가 정기 실행과 같은 실행기를 부른다 |
| 이어받기 | `StandaloneJob`으로 등록한다. 이어받은 서버와 관리자 수동 재실행이 넘겨준 날짜와 세대로 그 날짜만 집계한다 |
| 실패 | 날짜를 미처리 목록에 남기고 실행 기록을 FAILED로 닫는다 |

Spring Batch는 사용하지 않는다. 각 단계는 평범한 서비스이고 `DailyAggregationRunner`가 순서대로 부른다. 실행 점유와 이어받기는 [운영 배치 설계](../ops/런치캐치_배치_운영.md)를 따른다.

### 모으기와 공개

하루 결과는 모으기와 공개로 나눈다.

1. 모으기는 트랜잭션 없이 원천 도메인의 집계 조회 contract와 `event_log`를 읽어 메모리에 결과를 만든다.
2. 일시적 실패만 트랜잭션 밖에서 기다린 뒤 하루 결과를 처음부터 다시 모은다.
3. 공개는 한 트랜잭션에서 analytics 테이블의 하루 결과를 교체한다.
4. 검증 단계가 등식을 확인한 뒤 성공하면 미처리 날짜를 지운다.

공개 트랜잭션은 원천 도메인의 집계 조회를 호출하지 않는다. ops의 실행 세대 확인과 성공 기록만 함께 묶는다.

### 실행 세대

`acquire`는 claim, retry, rerun이 성공한 직후 실행 기록을 다시 읽어 그 시도의 세대(`attemptNo`)를 돌려준다. 세 호출이 세대를 돌려주지 않아서다. 잡은 행은 `updated_at`이 지금이라 2분 안에는 이어받기에 걸리지 않으므로 그때 읽은 세대가 이 시도의 세대다. 실행기는 이 값을 끝까지 들고 가고 중간에 다시 읽지 않는다.

모으기 단계 사이와 공개 직전에는 지금처럼 `renew`로 소유를 확인한다. 다른 서버가 이어받았으면 여기서 멈춘다. 같은 서버가 새 세대를 잡은 경우는 소유자가 같아 `renew`가 통과한다. 그래서 실행 기록과 미처리 날짜에 쓰는 일은 모두 `BatchAttemptGuard.assertCurrentAttempt`로 세대를 확인한 트랜잭션에서 한다(#587). 세대 확인은 실행 행을 잠그므로 커밋할 때까지 다른 서버가 이어받지 못한다.

| 쓰는 일 | 세대 확인과 한 트랜잭션에 묶는 것 |
|---|---|
| 공개 | 하루 결과 교체, 검증, 성공 기록(`succeed`), 미처리 날짜 삭제 |
| 실패 기록 | `fail`, 미처리 날짜 기록 |
| 재실행 기록 | `recordRetry` |

세대가 맞지 않으면 그 트랜잭션을 롤백하고 실행 기록과 미처리 날짜를 건드리지 않은 채 `LEASE_LOST`로 끝낸다. 성공 기록을 공개와 같은 트랜잭션에 넣은 이유는 [장애 처리 설계](./analytics_장애_처리_설계.md#애플리케이션-ops-실행-점유)에 있다.

### 수동 재집계와 이어받기

수동 재집계는 같은 실행기의 `acquire`와 `aggregate`를 사용하지만 정기 실행의 `runDate`가 하는 원천 해제 성공 확인을 거치지 않는다. 접수할 때 확보한 세대를 실행기 작업에 고정해 넘긴다. 실행기에서 기다리는 동안 소유자나 세대가 바뀌어도 새 세대를 자기 것으로 쓰지 않는다. 메모리 실행기는 한 스레드·대기열 네 개이며 서버 종료 시 작업을 잃을 수 있다. 시작 실패는 점유한 날짜를 FAILED·미처리로 남기지만 모든 중단에서 자동 복구된다는 보장은 없다.

`DailyAggregationJob`은 `StandaloneJob`으로도 등록한다. ops의 이어받기와 관리자 수동 재실행(`POST /v1/admin/batch-executions/DAILY_AGGREGATION/{영업일}:retry`)이 넘겨준 날짜와 세대로 그 날짜만 `aggregate`한다. 날짜를 다시 잡지 않고 01:00 전체 실행(`runAll`)도 부르지 않는다. 원천 해제 성공 확인은 수동 재집계와 같이 거치지 않는다.

* 집계가 실행 행을 처리 건수와 함께 먼저 닫는다. 이어서 ops가 부르는 건수 없는 `succeed`는 행이 이미 `RUNNING`이 아니라서 무시된다. 이벤트 로그 보존 삭제와 같은 임시 처리이고 `#559`, `#560`이 정리한다.
* 실행권을 잃어도 `run`은 예외를 던지지 않는다. 던지면 ops가 소유자 조건만으로 `fail`을 불러 같은 서버가 잡은 새 세대의 행을 실패로 닫는다.
* 던지지 않아도 이어지는 ops의 `succeed`가 같은 서버의 새 세대 행을 닫는 틈은 남는다. ops가 세대로 닫아야 막히고 `#591`이 맡는다.

### 단계 구성

| 단계 | 산출 테이블 | 원천 | 현재 구현 |
|---|---|---|---|
| `SettlementMetricsStep` | `daily_settlement_analytics` | `BillingStatsQueryService` | 구현됨, 순서 100 |
| `MemberMetricsStep` | `daily_member_analytics` | `MemberStatsQueryService`, `event_log` | 구현됨, 순서 200 |
| `StoreMetricsStep` | `daily_store_analytics` | `StoreStatsQueryService`, `CampaignStatsQueryService` | 구현됨, 순서 300 |
| `FunnelMetricsStep` | `daily_funnel_analytics` | 광고 서빙·쿠폰 집계 조회, `event_log` | 구현됨, 순서 400 |
| `InvalidMetricsStep` | `daily_invalid_analytics` | `AdservingStatsQueryService` | 구현됨, 순서 500 |
| `NotBilledMetricsStep` | `daily_campaign_not_billed_analytics` | 정산·광고 서빙 집계 조회 | 구현됨, 순서 510 |
| `FraudMetricsStep` | `daily_campaign_fraud_analytics` | `AdservingStatsQueryService` | 구현됨, 순서 520 |
| `CampaignReportStep` | `campaign_daily_report`, 사유·시간대·슬롯·변경 이력 | 정산·광고 서빙·쿠폰 집계와 캠페인 연결 정보 | 설계만 있음 |
| `MemberUsageStep` | `daily_member_usage` | `CouponStatsQueryService.dailyMemberStats`, `event_log` | 구현됨, 순서 700 |
| `SettlementConsistencyStep` | 지표와 구조화 로그 | 정산 집계 조회와 공개한 매출 행 | 구현됨, 순서 900 |

A-04와 A-08을 구현하지 않으므로 `daily_segment_analytics`와 `daily_owner_spend`를 채우는 단계는 두지 않는다. 두 테이블은 폐기하지 않고 남긴다. A-03의 전용 테이블과 퍼널 단계의 기준은 [API 설계의 퍼널 절](./analytics-api-design.md#퍼널)을 따른다. 캠페인 연결 정보의 원본 설계는 `CampaignQueryService.findAllById`이며 없는 집계 조회 메서드를 전제로 만들지 않는다.

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

billing은 `BillingStatsQueryService`를 공개했고 `SettlementMetricsStep`과 `SettlementConsistencyStep`이 사용한다. 아래 표의 후속 단계는 위 구현 상태를 함께 본다. 귀속 기준과 합계의 정의는 [billing 구조와 동작 설계](../billing/billing_구조와_동작_설계.md#집계-조회의-영업일-귀속)가 소유한다.

| 메서드 | 돌려주는 것 | 쓰는 단계 |
|---|---|---|
| `ledgerSums(영업일)` | 점주와 캠페인별 `CHARGE`, `RESERVE`, `DEDUCT`, `RELEASE`, `REFUND`와 양수·음수 `ADJUST` 합 | `SettlementMetricsStep`, `SettlementConsistencyStep`, 후속 `CampaignReportStep` |
| `billingCounts(영업일)` | 캠페인별 `BILLED`, `NOT_BILLED_BUDGET`, `NOT_BILLED_PAUSED`, `NOT_BILLED_NO_PLAN` 건수 | `NotBilledMetricsStep`, `CampaignReportStep` |
| `dailyBudgets(영업일)` | 캠페인별 그날 적용 하루 예산과 단가 | `CampaignReportStep` |
| `dailyTotals(영업일)` | 그날 말 미소진 잔액, 원장 대조용 합계, 원장 불일치 건수 | `SettlementMetricsStep`은 미소진 잔액을 쓴다. 나머지 필드는 현재 단계에서 검증에 사용하지 않으며 A-07 응답에도 없다 |
| `correctionTargetDates(기록 영업일)` | 그날 기록된 양수 `ADJUST`가 가리키는 정정 대상 영업일 | 재집계 대상 찾기 |

- 합계의 부호는 저장 부호 그대로다. `RESERVE`, `DEDUCT`, `REFUND`와 음수 `ADJUST`는 0 이하다. 소진액 계산은 `SpendFormula`가 한 곳에서 한다.
- `correctionTargetDates`는 그날 집계 뒤에 과거 영업일을 다시 집계할 날짜를 찾는 입력이다. 반환된 날짜를 `aggregation_pending_date`에 `ADJUSTED`로 남겨 다음 실행이 다시 집계하게 한다. 기록 영업일과 같은 날짜가 섞여 올 수 있어 같은 날은 거른다.
- 정정은 영업일 합계에서는 정정 대상 영업일에, 미소진 잔액과 `dailyTotals.ledgerSum`에서는 기록한 날에 들어간다. 그래서 기록한 날의 `ledgerSum`과 대상 영업일의 `ledgerSums`는 서로 다른 몫이다.
- 끝나지 않은 영업일을 넘겨도 billing은 막지 않고 그 시점까지 기록된 값을 돌려준다.

### 공개 규칙

아래 리포트의 ID·자식·이력 규칙은 `CampaignReportStep`을 만들 때 적용할 설계다. 현재 구현된 세 일별 지표는 `replace`로 그날 행을 교체한다.

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

화면 조회 이벤트 `poster_open`, `map_view`, `wishlist_view`는 A-13 HTTP 요청으로 받는다.

현재 수신자는 광고 서빙과 쿠폰의 여섯 이벤트 타입을 받는다. `SwipedEvent`가 wish와 pass를 나눠 쓰므로 서버 이벤트 유형은 일곱 개다. 원본에서 제외한 알림 발송 이벤트는 수신 메서드가 없고, 스키마의 `notification_sent` 값만으로 구현됐다고 보지 않는다. 실행기와 실패·거절 지표의 상세는 PR #459가 보완하는 구조와 동작 설계의 "퍼널 이벤트 수신과 비동기 기록"이 갖는다.

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

`EventLogRetentionJob`이 매일 04:00 KST에 13개월보다 오래된 행을 1000건씩 삭제한다. 작업명은 `EVENT_LOG_RETENTION`이고 `batch_execution_log`의 점유를 이긴 서버만 돈다. 01:00 집계가 끝난 뒤의 새벽이며 서빙 휴지 구간이 없어졌으므로 시각 자체에 대한 의존은 없다.

삭제 선은 오늘에서 `DailyAggregationRunner.RETENTION_MONTHS`를 뺀 날짜의 00:00이다. 재집계를 허용하는 가장 오래된 영업일(`oldestRetained`)과 같은 날짜라서, 재집계할 수 있는 날짜의 원본 이벤트는 지워지지 않는다. 청크 사이에는 짧게 쉬어 이벤트 기록 쓰기와 락을 다투는 시간을 줄이고, 청크마다 자기 트랜잭션으로 커밋한다.

작업은 `StandaloneJob`으로도 등록돼 있어 멈춘 실행을 다른 서버가 이어받을 수 있다. 삭제는 멱등이라 이어받아 다시 돌려도 남은 행만 지운다. `daily_member_usage`는 누적 계약을 위한 집계라 이 삭제 대상에서 제외한다.
