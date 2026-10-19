# 결제·포인트·정산 (billing) 배치와 내부 흐름 설계

**2026-10-12 분리:** 정산의 배치와 내부 흐름 설계를 이 문서로 나눴다.

이 문서에서 "NN행"은 요구사항 명세서(`V51_런치캐치_요구사항명세서.xlsx`) 기능 명세서 시트의 행 번호다. 다른 문서가 쓰는 FR 번호는 이 행 번호에서 3을 뺀 값이다(98행 = FR-095). 비기능, 비즈니스 규칙, 도메인 정의처럼 다른 시트의 행은 시트 이름을 함께 적었다.

HTTP가 아니라 **모듈 간 호출·이벤트·배치**다. 점주·관리자 API는 `billing-api-spec.md`에서 다룬다.

| 근거 | 기준 |
| --- | --- |
| 요구사항 | V51 **98행**(하루 예산 배분과 예산 소진 진행률), **103행**(노출 차감 및 원장 기록), **51행**(잔액 부족 시 게재 중단), **49행**(충전 후 재예약) |
| 참조만 하는 행 | 캠페인 47·48행, 광고 서빙 99·100행, 운영 102행(스케줄러)·104행(감사 로그) |
| 저장 구조 | `billing/V1__billing_init.sql`의 `budget_daily_plan`, `budget_hourly_target`, `deduction_result`, `point_ledger` |
| 도메인 규칙 | 저장소 `docs/architecture/런치캐치_도메인_구조와_의존성_설계.md` 2.2(조회 인터페이스), 2.4(명령 인터페이스와 이벤트) |

점주·관리자 API의 계약은 [billing API 명세](../../api-spec/billing-api-spec.md)가, 그 API를 서버가 처리하는 방식은 [billing API 설계](./billing-api-design.md)가 갖는다. 노출 차감부터 대사까지의 구조는 [구조와 동작 설계](./billing_구조와_동작_설계.md)가 갖는다.

**저장 구조가 요구사항과 다르면 마이그레이션을 따른다.** 기준은 `src/main/resources/db/migration/billing/`의 마이그레이션이다. 그 밖의 스키마 사본은 사람이 손으로 맞추는 것이라 기준으로 삼지 않는다.

---

## 목록

| # | 이름 | 행 | 성격 |
| --- | --- | --- | --- |
| I-01 | 노출 차감 → 판정 기록·원장 기록 | 103 (참조 99·100) | 광고서빙이 `billing.contract.PointDeductor` 직접 호출 |
| I-02 | 00:00 예약·해제 작업 | 98, 102 (3)(4)(5)(7), 51 | 운영 스케줄러에 등록하는 배치 *(2026-10-19 수정, #497: 서빙이 하루 종일이 되어 13:30 해제를 없앴다. 해제는 00:00 예약 단계가 전날 몫을 정식으로 한다)* |
| I-03 | 충전 직후 재예약 | 48, 49, 51 | billing 내부 `PointCharged` 핸들러 (2026-10-12 수정: 이름에서 `Event`를 뺐다) |
| I-04 | 결제 재조회 워커(아웃박스) | 22, 49 | 주기 스케줄러 |
| I-05 | 정산 대사 배치 2종 | 102, 103 | 배치 |
| I-06 | 예산 소진 진행률 조회 | 98 | 광고서빙이 `billing.contract.BudgetQueryService` 호출 |
| I-07 | 캠페인 상태 변경 수신 | 51, 103 | `campaign.contract.CampaignStatusChangedEvent` 리스너 |
| I-08 | 분석 집계 조회 | analytics 설계 | 분석의 일별 집계 배치가 `billing.contract.BillingStatsQueryService` 호출 *(2026-10-16 추가, #352, #402)* |

### 정산이 쓰는 다른 도메인의 공개 기능

| 방향 | 인터페이스 | 무엇 |
| --- | --- | --- |
| billing → campaign | `campaign.contract.CampaignQueryService` | 00:00 예약 대상과 다음 영업일 적용 하루 예산, 충전 재예약 대상(`PAUSED(NO_POINTS)` 캠페인), 환불 거부 조건의 집행 중 캠페인 |
| billing → campaign | `campaign.contract.CampaignFundingUpdater` | 예약 결과와 판정 반영. 잔액 부족이면 `PAUSED(NO_POINTS)`, 재개 판정이면 `ACTIVE`로 **상태만** 바꾼다 |
| billing → campaign | `campaign.contract.CampaignQueryService.findAllById` | **2026-10-14:** 포인트 조정이 닫으려는 `CAMPAIGN_SPEND_VS_LEDGER` 대사 건의 캠페인 점주 확인(`billing-api-design.md` 「포인트 조정」) |
| billing → campaign | `campaign.contract.CampaignQueryService.findByIdForUpdate` | **2026-10-16(#378):** 충전 직후 재예약(I-03)이 캠페인마다 원장을 쓰기 전에 그 캠페인을 다시 잠그고 최신 상태를 확인한다. 기존 간선이라 CODEOWNERS는 바뀌지 않는다 |
| billing → owner | `owner.contract.OwnerStatusQuery.findStatus` | **2026-10-14:** 포인트 조정 대상 점주가 있는지 확인. 새 간선이라 [도메인 구조와 의존성 설계](../../architecture/런치캐치_도메인_구조와_의존성_설계.md) 2.2절과 CODEOWNERS를 같은 PR에서 고친다 |
| billing → ops | 운영의 설정값 조회 기능 | 노출 단가(아래 I-02 참고) |
| adserving → billing | `billing.contract.PointDeductor` | I-01 |
| adserving → billing | `billing.contract.BudgetQueryService` | I-06 |
| analytics → billing | `billing.contract.BillingStatsQueryService` | I-08 |
| campaign → billing | `CampaignStatusChangedEvent` | I-07 |

**정산은 도메인 간 이벤트를 발행하지 않는다.** `PointCharged`(I-03)는 billing 내부 통보다. **2026-10-12 수정:** 저장소 아키텍처 테스트가 `Event`로 끝나는 타입을 발행 도메인의 `contract`에 두라고 요구하므로 공개하지 않는 이 통보는 이름에서 `Event`를 뺐다.

현재 구현이 사용하는 공개 계약은 다음과 같다. 메서드 시그니처의 기준은 각 도메인의 `contract`다.

| 인터페이스 | 메서드 | 쓰는 곳 |
| --- | --- | --- |
| `CampaignQueryService` | `findServableOn(businessDate)` | 00:00 예약 대상을 광고 서빙과 같은 조건으로 읽는다 |
| `CampaignQueryService` | `findByOwnerIdAndStatusIn(ownerId, statuses)` | I-03의 `PAUSED(NO_POINTS)` 조회 |
| `CampaignQueryService` | `hasLiveCampaign(ownerId)`, `findOwnerIdsWithLiveCampaign(ownerIds)` | 환불 가능 여부와 목록의 집행 중 여부를 읽는다 |
| `CampaignQueryService` | `findByIdForUpdate(campaignId)` | 충전 재예약 직전에 캠페인을 다시 잠그고 상태를 확인한다 |
| `CampaignFundingUpdater` | `applyFundingJudgement(FundingJudgement)` | 00:00 판정과 I-03 재개를 반영한다 |
| `PlatformSettingQueryService` | `getImpressionUnitPrice()` | 00:00 단가 스냅샷을 읽는다 |

---

# 노출 하나가 거치는 두 단계 판정

노출 1건은 **서로 다른 도메인에서 두 번** 판정된다. 두 판정은 묻는 것이 다르고 서로를 덮어쓰지 않는다. 같은 노출이 1단계에서 `VALID`이면서 2단계에서 `NOT_BILLED_BUDGET`인 조합은 정상이고 흔하다.

| | 1단계 — 유효한 노출인가 | 2단계 — 과금할 수 있는가 |
| --- | --- | --- |
| 판정 주체 | 광고 서빙 | 정산 |
| 시점 | 노출 적재 시 | 같은 트랜잭션 안(I-01) |
| 저장 위치 | `impression_log.validity`, `impression_log.invalid_reason` | `deduction_result.billing_status` |
| 묻는 것 | 이 노출을 **집계에 넣어도 되는가** | 이 노출에 **포인트를 뺄 수 있는가** |
| 값 | `VALID` / `INVALID`(+사유) | `BILLED` / `NOT_BILLED_BUDGET` / `NOT_BILLED_PAUSED` |

## 1단계 — 광고 서빙의 유효성

| 값 | 사유 | 언제 |
| --- | --- | --- |
| `VALID` | — | 서빙 토큰이 살아 있고, 서빙을 받은 회원이 노출을 보고했다 |
| `INVALID` | `EXPIRED_OR_UNKNOWN` | 서빙 토큰을 확인할 수 없다 (아래) |
| `INVALID` | `NOT_OWNER` | 서빙을 받은 회원과 노출을 보고한 회원이 다르다 |

`EXPIRED_OR_UNKNOWN` 하나가 **서로 다른 세 상황**을 덮는다. 저장된 값만으로는 셋을 구분할 수 없다.

| 상황 | 실제로 일어난 일 |
| --- | --- |
| TTL 만료 | 서빙 후 30분이 지나 캐시의 `serve_id`가 사라졌다 |
| 위조·오염 | 발급한 적 없는 `serve_id`가 올라왔다 |
| 재전송 | 이미 노출로 쓴 `serve_id`를 다시 보냈다. 1 서빙 1 노출이라 첫 노출에서 키를 지우므로, 두 번째부터는 만료와 똑같이 보인다 |

읽는 쪽 주의: **여러 캠페인에서 같은 시각에 함께 치솟으면 부정 노출보다 장애를 먼저 의심한다.** 캐시 재시작이나 클라이언트 재시도 폭주가 같은 모양으로 보인다.

## 2단계 — 정산의 과금 여부

| 값 | 언제 | 어디서 |
| --- | --- | --- |
| `BILLED` | 당일 소진 + 단가 ≤ 그날 예약액 | I-01 |
| `NOT_BILLED_BUDGET` | 그날 예약액에 도달 | I-01 |
| `NOT_BILLED_PAUSED` | 캠페인이 중단된 뒤 도착 | I-01, I-07의 중단 표시를 본다 |
| `NOT_BILLED_NO_PLAN` | 노출을 받은 날에 그 캠페인의 계획이 없음 *(2026-10-19 추가, #497)* | I-01 |
| `NOT_BILLED_SOLD_OUT` | 품절 상태에서 난 노출 | **정산이 쓰지 않는다.** 차감을 부르지 않으므로 광고서빙이 `impression_log.sold_out`에 남기고 리포트가 `serve_id`로 조인한다 |

## 두 판정이 집계에서 만나는 규칙

| 규칙 | 근거 |
| --- | --- |
| **미과금 노출도 유효 노출이다.** 예산을 넘겨 과금되지 않은 노출은 노출 수에 들어간다 | V51 103행 |
| 예산을 넘긴 노출은 `invalid_reason`이 아니라 `deduction_result.billing_status = NOT_BILLED_BUDGET`에 남는다 | 저장 구조 |
| 무효 노출(1단계 `INVALID`)은 차감을 부르지 않으므로 `deduction_result`에 행이 생기지 않는다 | I-01 |
| **429(요청 한도 초과)는 두 단계 어디에도 없다.** 서빙이 거절된 요청이라 `serve_id`가 없고, `impression_log`는 `serve_id`가 PK의 일부이며 `displayed_at`과 좌표 2개가 NOT NULL이라 행을 만들 수 없다 | 저장 구조, V51 28행·비기능 38행이 429를 노출과 따로 둠 |

---

# I-01. 노출 차감 → 판정 기록·원장 기록

광고서빙이 노출을 저장하는 **같은 DB 트랜잭션(REQUIRED)** 안에서 `billing.contract.PointDeductor`를 **직접 호출**한다. 노출 저장과 판정·원장 기록이 함께 커밋·롤백된다.

```
PointDeductor.deduct(serveId, campaignId, businessDate)
  — owner_id 와 단가는 budget_daily_plan 에서 읽는다
```

*(2026-10-19 수정, #497)* `businessDate`는 **노출 요청을 서버가 받은 날(KST)**이다. 카드를 내려준 날이 아니다. 전날 예산은 다음 날 00:00에 해제되므로(I-02 「해제」) 00:00 뒤에 전날로 차감하면 돌려준 예산에서 빠진다. 호출하는 광고 서빙의 변경은 #498이 한다.

1. 원자 연산으로 `당일 소진 + 단가 ≤ 그날 적용 하루 예산(예약액)`을 검사한다. 검사와 차감은 나눌 수 없는 하나의 동작이다.
2. 판정을 `deduction_result`(`UNIQUE(serve_id)`)에 기록한다.

| 판정 | 언제 |
| --- | --- |
| `BILLED` | 검사 통과 |
| `NOT_BILLED_BUDGET` | 그날 예약액에 도달 |
| `NOT_BILLED_PAUSED` | 캠페인이 중단된 뒤 도착 (I-07의 중단 표시를 본다) |
| `NOT_BILLED_NO_PLAN` | 노출을 받은 날에 그 캠페인의 계획 행이 없음. 00:00 예약이 그 캠페인의 계획을 만들기 전이거나 전날 집행이 끝난 캠페인이다. serve TTL이 30분이라 자정 직후 30분 안에서만 생긴다 *(2026-10-19 추가, #497, billing V8)* |

`NOT_BILLED_NO_PLAN`은 계획이 없으니 `owner_id`를 캠페인(`CampaignQueryService.findAllById`)에서, 단가를 운영 단가(`PlatformSettingQueryService.getImpressionUnitPrice`)에서 읽어 판정 행에 남긴다. 과금하지 않아 카운터와 원장은 건드리지 않는다. 행을 남겨야 광고 서빙의 유효 노출 수가 판정 합에 품절 뒤 노출을 더한 값과 같다. 캠페인 자체가 없으면 예외다.

**조건 (2026-10-19, #497, #498):** 노출 인정 시간은 광고 서빙의 serve 기록 TTL(1800초)이 정한다. 정산에는 이 값이 없다(`impression-valid-for`를 지웠다). 그래서 `NOT_BILLED_NO_PLAN`이 생기는 범위(자정 직후 TTL 만큼)와 장중 중단 뒤 미과금이 생기는 범위(I-07)는 이 TTL에 기댄다. TTL을 바꾸면 이 범위도 함께 바뀐다. 광고 서빙은 같은 조건을 `adserving_구조와_동작_설계.md`에 적는다(#498).

`NOT_BILLED_SOLD_OUT`은 차감을 부르지 않으므로 여기 없다. 광고서빙이 `impression_log.sold_out`에 기록하고 리포트가 `serve_id`로 조인한다.

3. `BILLED`이면 `point_ledger`에 `DEDUCT(amount = -unit_price, serve_id, campaign_id, owner_id, source=REALTIME)`를 기록한다. `owner_id`는 `budget_daily_plan.owner_id`다.
4. 멱등: `UNIQUE(serve_id)`와 `UNIQUE(serve_id, entry_type)` 위반은 **이미 처리된 건으로 보고 성공 처리**하며, 판정 기록 1건과 카운터 증가 1회만 남는다.
5. 예약액에 도달하면 **차감만 중단한다.** 노출은 노출 상한(예약액 ÷ 단가 × 1.05, 광고서빙 99행)까지 계속되고 캠페인 상태는 바뀌지 않는다.
6. DB 쓰기가 실패하면 예외로 트랜잭션 전체를 롤백한다(노출 저장도 롤백). 별도 `ledger_outbox`는 두지 않는다. 롤백되면 정산이 곧바로 카운터의 처리 표시와 소진을 되돌린다(2026-10-14, 구현 순서 F-6). 되돌리기가 실패해 카운터만 증가한 채 남은 경우는 그날 안에 고쳐지지 않고 다음 날 00:00 카운터 대조(I-05)가 불일치로 기록한다(2026-10-19 수정, #497).
7. **이 경로는 잔액을 읽지 않는다.** 그날 예약액이 이미 잔액에서 빠져 있어 잔액은 음수가 될 수 없다.

성능 목표는 초당 10,000건이다(비기능 17행).

---

# I-02. 00:00 예약·해제 작업

운영 도메인 스케줄러(102행)에 등록하는 작업이다. 단계 `@Order`는 102행 단계 번호 × 10이다. *(2026-10-19 수정, #497: 서빙 시간대(10:00~12:59)를 없애 광고 서빙이 하루 종일 카드를 내려준다. 서빙이 끝난 뒤 돌던 13:30 해제 작업은 없앴고 해제는 다음 날 00:00 예약 단계가 맡는다)* 실행 이력은 운영의 `batch_execution_log`에 `UNIQUE(job_name, business_date)`로 남는다.

00:00 작업은 순서 의존이 있어 **앞 단계가 실패하면 뒤 단계를 실행하지 않고 중단**하며, 실패한 작업명과 사유를 기록해 관리자에게 알리고 **실패한 단계만 수동으로 재실행**할 수 있다. 배치 서버는 2대이고, 실행 중인 서버의 생존 신호(`updated_at`, 30초 주기)가 2분 넘게 끊기면 다른 서버가 이어받아 **끝나지 않은 단계부터** 다시 실행한다(`owner_id`가 이어받은 서버로 바뀐다). 일시적 오류(교착, 락 대기 초과, 연결 오류, Valkey 시간 초과)는 30초·1분 간격으로 최대 2회 자동 재실행한다(`retry_count`).

## 00:00 작업

정산이 구현하는 단계는 (3)(4)(5)(7)이고, (6)은 정산이 판정해 캠페인이 상태에 반영한다. 나머지는 다른 도메인 소유다.

| 단계 | 작업 | 내용 |
| --- | --- | --- |
| (3) | 예산 초기화 | 당일 소진 카운터·예약액 키 초기화. 점주가 바꿔 둔 하루 예산 설정값이 있으면 이때 적용한다(98행, 45행) |
| (4) | 전일 카운터↔원장 대조 | `CAMPAIGN_SPEND_VS_LEDGER`(I-05) |
| (5) | 예약 | ① 전날 남은 예약을 `RELEASE`(2026-10-19 수정, #497: 전날의 정식 해제다. 아래 「해제」) ② 집행 기간 안의 `ACTIVE`·`PAUSED(OWNER)`·`PAUSED(NO_POINTS)` 캠페인마다 `min(설정 하루 예산, 잔액)`을 `RESERVE(−)`로 기록하고 `budget_daily_plan`(`owner_id`, `unit_price`, `daily_budget` = 그날 예약액)을 만든 뒤 예약액 카운터를 세팅. **2026-10-12 수정: 잔액이 모든 캠페인 합보다 작으면 캠페인 ID 오름차순으로 앞에서부터 채운다**(V51 34행의 동률 기준과 같은 순서). 잔액이 0이 된 뒤의 캠페인은 예약액 0으로 계획 행만 만들고 (6)에서 `NO_POINTS`로 판정된다 |
| (6) | 잔액 부족 판정 | 예약액 < 단가이면 `NO_POINTS`. **대상은 `ACTIVE`와 `PAUSED(NO_POINTS)`만이다** — `PAUSED(OWNER)`는 점주의 재개 요청 때 48행 규칙으로 판단한다 |
| (7) | 시간대별 목표 계산 | `budget_hourly_target`에 0~23시 `target_amount` 24행(2026-10-19 수정, #497: 기본 가중은 `billing.schedule.hourly-weights` 24개이고 I-06에 있다). **대상은 (6)까지 확정된 `ACTIVE`만이다.** `UNIQUE(budget_daily_plan_id, hour)`로 재실행 멱등. 일별 노출 목표 = 그날 적용 하루 예산 ÷ 단가 |

**2026-10-16 구현 상태:** 00:00 묶음의 네 단계는 운영의 `DailyJob`으로 구현돼 있다. (3) 예산 초기화는 전용 작업이 없고 (5) 예약이 해시를 준비한다.

| 단계 | 작업명 | `@Order` | 구현 |
| --- | --- | --- | --- |
| (4) 전일 카운터↔원장 대조 | `BILLING_SPEND_RECON_PREV` | 40 | `PreviousDaySpendReconciliationJob` |
| (5) 예약 | `BILLING_RESERVE` | 50 | `DailyReservationJob` |
| (6) 잔액 부족 판정 | `BILLING_FUNDING_JUDGE` | 60 | `FundingJudgementJob` |
| (7) 시간대별 목표 | `BILLING_HOURLY_TARGET` | 70 | `HourlyTargetJob` |

## 해제

*(2026-10-19 수정, #497)* 13:30 작업(`DailyReleaseScheduler`, 당일 대조와 해제)을 없앴다. 서빙이 하루 종일이라 13:30에도 차감이 들어와, 그 시각에 해제하면 이미 돌려준 예산에서 차감된다.

해제는 다음 날 00:00 묶음이 한다. (4) 전일 대조가 먼저 돌고 (5) 예약이 전날 캠페인별 (그날 `RESERVE` 합 − 그날 `DEDUCT` 합)이 양수이면 `RELEASE(+)`를 기록한 뒤 오늘 예약을 한다. 이 순서가 맞으려면 00:00 뒤에 전날로 들어오는 차감이 없어야 한다. 그래서 노출 차감의 영업일을 **노출 요청을 서버가 받은 날**로 정했다(`PointDeductor` 계약, I-01). 23:59에 내려준 카드의 노출이 00:10에 오면 새 날 예산에서 차감된다.

예약 단계가 끝나면 `(BILLING_DAILY_RELEASE, 전날)`을 `SUCCESS`로 남긴다. 분석의 01:00 집계가 이 행으로 전날 원천이 확정됐는지 본다. 이름은 13:30 작업의 것을 그대로 써서 분석 코드는 바뀌지 않는다. 해제는 끝났지만 예약이 실패한 날은 표시가 늦어지고, 그 단계가 다시 돌아 성공할 때 남는다.

점주에게는 미소진 예약이 잔액으로 돌아오는 시각이 그날 13:30에서 다음 날 00:00으로 늦어진다.

## 판정 통보

```
CampaignFundingUpdater: { campaignId, ownerId, businessDate,
                          judgement: NO_POINTS | RESUME | OK, cause: DAILY | RECHARGE,
                          reservedAmount, unitPrice, configuredDailyBudget, todaySpend }
```

**정산이 캠페인을 직접 호출한다.** V51 `검증 시나리오` 27행이 "판정이 정산의 직접 호출로 바로 끝나므로 유실이 없다"로 못 박았다(이벤트 발행이 아니다). 51행의 필수 데이터가 캠페인 ID, 점주 ID, 그날 예약액, 노출 단가, 설정 하루 예산, 당일 소진이므로 네 금액을 함께 넘긴다. 캠페인은 이 값을 보관하지 않고 판정 근거 표시·로그용으로만 받는다.

## 단가와 하루 예산 하한의 출처

(5)에서 `budget_daily_plan.unit_price`에 찍을 값은 **운영 도메인의 `platform_setting`에서 읽는다.** `point_policy`는 2026-10-05에 이 값들을 지우고 운영으로 넘겼다(V51 `도메인 정의` 14행이 "플랫폼 설정값(노출 단가, 최소와 부트스트랩 하루 예산 포함)"을 운영 소유로 둔다). 운영은 모든 도메인이 읽는 하위 계층이라 의존 간선이 늘지 않는다(도메인 규칙 5).

| `setting_key` | 값 | 출처 |
| --- | --- | --- |
| **`IMPRESSION_UNIT_PRICE`** | 기본 10 (1포인트 = 1원) | `미결정 사항` #2 — **값은 미결정**(팀장) |
| `MIN_DAILY_BUDGET` | 1000 | `미결정 사항` #13 결정 완료 |
| `BOOTSTRAP_DAILY_BUDGET` | 3000 | `미결정 사항` #13 결정 완료 |

- **정산이 읽는 것은 `IMPRESSION_UNIT_PRICE` 하나다.** 나머지 둘은 점주의 하루 예산 설정·추천 화면이 쓰는 값이다(캠페인 45행).
- `setting_value`는 정수 문자열로 저장하고 `PlatformSettingQueryService.getImpressionUnitPrice()`가 읽는다. `DailyReservationService`는 반환된 단가가 0 이하면 예약을 중단한다. 예약 시점에 읽은 단가가 그날 계획의 스냅샷이 된다.
- **`setting_key`에 CHECK를 걸지 않는다.** [마이그레이션 규칙](../../../src/main/resources/db/migration/migration.md)이 "값이 자주 늘어나는 열에 CHECK를 걸면 값 하나를 더할 때마다 테이블 복사가 일어난다"고 경고한다. 그래서 **오타를 DB가 막아주지 않는다.** 키 이름이 어긋나면 조회가 빈 결과를 돌려주고 조용히 실패한다.
- **그래서 키를 못 찾으면 예약 작업을 중단한다.** 0이나 `null`로 진행하면 예약액이 전부 0이 되어 모든 캠페인이 `NO_POINTS`로 전이한다. 예외를 던져 `batch_execution_log`에 `FAILED`와 사유를 남기고 뒤 단계를 실행하지 않는다. 단가가 0 이하이거나 정수로 파싱되지 않는 경우도 같다.
- **단가의 "익일 적용"은 별도 장치가 필요 없다.** `platform_setting`에 `effective_date`가 없지만, 00:00 배치가 그 시점의 현재 값을 읽어 `budget_daily_plan.unit_price`에 스냅샷하므로 오늘 바꾼 값이 내일부터 적용된다(`비즈니스 규칙` 11행). 점주 하루 예산이 (3)단계에서 적용되는 것과 같은 메커니즘이다.

## 그 밖의 규칙

- `RELEASE`와 정기 `RESERVE`는 `payment_id`가 `NULL`이고, 멱등은 생성 컬럼 `day_reserve_dedup_key`(캠페인×영업일×유형) UNIQUE가 맡는다.
- `RESERVE` 계산의 잔액은 전날 `RELEASE`(00:00 보정분 포함) 이후 원장에서 계산한 잔액(`DEDUCT` 제외)이다. **이월은 없다.**
- 예약액이 `0`이면 원장에 기록하지 않고(`RESERVE`는 음수만 허용) `budget_daily_plan.daily_budget = 0`으로 두고 판정만 통보한다. V2의 CHECK는 `daily_budget >= 0`이다.
- `RESERVE`·`RELEASE`는 `source=RECONCILIATION`으로 기록한다(배치 컨텍스트).
- 당일 소진 카운터에 장애가 나면 원장으로 재구성한다. 재구성은 대조 작업이 계산해 정산이 자기 카운터에 직접 반영하고, 장애 직후에는 **수동으로 실행**한다(98행 예외) — 경로는 아래 "아직 정해지지 않은 것" 참고.

---

# I-03. 충전 직후 재예약

승인 확정, 웹훅, 재조회 워커 어디서든 `CHARGE`가 확정되면 커밋 후 발행하는 billing 내부 이벤트다.

**2026-10-09 수정:** `CHARGE` 행이 **새로 만들어진** 커밋 뒤에는 경로와 관계없이 발행한다. 승인 확정(`POST /v1/owner/payments/{paymentId}:confirm`), 웹훅, 재조회 워커가 승인 확정 로직을 하나의 서비스 메서드로 공유하고 그 안에서 발행한다. 이미 `APPROVED`라 `(payment_id, CHARGE)` UNIQUE에 걸려 새 행이 없으면 발행하지 않는다. **`LEDGER_CORRECTION`이 만든 `CHARGE` 보정은 과거 영업일 값을 고치는 것이므로 발행하지 않는다.**

```
PointCharged { ownerId, paymentId, amount }
```

**2026-10-16 수정(#319):** 아래 1~7을 실제 구현에 맞춰 다시 적었다. 캠페인 하나가 아니라 여럿을 캠페인 ID 오름차순으로 채우고, 각 캠페인은 원장을 쓰기 전에 다시 잠근다.

1. `CampaignQueryService.findByOwnerIdAndStatusIn`로 이 점주의 `PAUSED(NO_POINTS)` 캠페인 중 집행 기간 안인 것을 캠페인 ID 오름차순으로 읽는다. 없으면 끝.
2. *(2026-10-19 수정, #497: 서빙이 하루 종일이라 시각 제한을 없앴다. 00:00 예약 전이라 그날 계획이 없는 캠페인은 건너뛰고 그 예약이 충전 뒤 잔액으로 배분한다. 00:00 예약과 겹쳐도 둘 다 점주 계정 행을 먼저 잠가 차례로 돈다)*
3. 점주 계정 행을 잠그고 잔액을 읽는다.
4. **캠페인마다, 원장을 쓰기 전에 그 캠페인을 `CampaignQueryService.findByIdForUpdate`(#378)로 다시 잠그고 최신 상태를 확인한다.** `PAUSED(NO_POINTS)`가 아니면(1에서 읽은 뒤 다른 트랜잭션이 이미 재개했거나 관리자·점주가 중단했거나 종료됐으면) 건너뛰고 남은 잔액을 다음 캠페인에 넘긴다. `CampaignStatusService.resumeFromFunding`도 같은 방식으로 잠그고 재확인하지만 그건 캠페인 자신의 상태 전이만 지켜줄 뿐, billing이 그보다 먼저 쓰는 아래 6의 원장과 `daily_budget` 증가는 지켜주지 못해 이 확인이 따로 필요했다(리뷰로 발견).
5. `새 예약액 = min(설정 하루 예산, 기존 예약액 + 남은 잔액)`. 기존 예약액은 그 계획 행의 `daily_budget`에서 읽는다.
6. 늘어난 만큼만 `RESERVE(-delta, payment_id, campaign_id)`를 기록하고 `budget_daily_plan.daily_budget`과 당일 소진 해시의 예약액(`billing_day_reserve_increase.lua`, 해시가 있고 지금 값보다 큰 값일 때만 올린다)을 새 값으로 갱신한다. **멱등은 결제×캠페인 생성 컬럼 UNIQUE(`uk_point_ledger_recharge_reserve_dedup`, `payment_id|campaign_id`)로, 한 결제로 캠페인 여럿을 재예약할 수 있다.** 결제당 한 줄만 허용하는 `uk_point_ledger_payment_entry`는 `entry_type <> 'RESERVE'`로 좁혀 CHARGE 전용으로 남겼다(`V6__billing_recharge_reserve_dedup.sql`).
7. **재개 판정은 정산이 한다**(새 예약액 ≥ 단가). `CampaignFundingUpdater`에 `cause=RECHARGE`로 통보하면 캠페인이 상태만 `ACTIVE`로 바꾼다. 관리자가 중단한 캠페인(`PAUSED(ADMIN)`)과 집행 기간이 끝난 캠페인은 재개하지 않는다(51행 예외).

재개된 캠페인의 시간대별 목표 재계산은 I-07이 맡는다(48행).

자리는 `billing.listener.PointChargedListener`(`@TransactionalEventListener(AFTER_COMMIT)`)이고 `RechargeReservationService` 하나만 부른다. 실패를 삼켜 ERROR로만 남긴다. 충전은 이미 커밋돼 있어 올리면 승인 확정 API·웹훅·재조회 워커가 성공한 충전에 실패를 돌려준다. 해시 반영(6)이 실패해도 지우지 않고 ERROR로만 남긴다 — 다른 해시 미러(상태 변경)는 실패하면 지워 DB 정본으로 다시 만들게 하지만, 여기서는 예약액이 실제보다 작게 보여 과금이 일찍 멈추는 쪽으로 남기는 선택이다.

---

# I-04. 결제 재조회 워커 — 아웃박스

`payment_reconciliation_outbox`는 PG 응답이 불명확한 결제(`UNKNOWN`)를 확정하기 위한 재시도 큐다.

- **적재**: 승인 확정에서 결제를 `UNKNOWN`으로 만들 때 *(2026-10-08 수정: 웹훅은 UNKNOWN을 만들지 않는다. [billing API 설계](./billing-api-design.md)의 웹훅 절 참고)* 같은 트랜잭션에서 `status=PENDING`, `attempt_count=0`, `max_retry`, `next_retry_at`을 채워 넣는다. 결제당 1행(`UNIQUE(payment_id)`).
- **처리**: 주기 스케줄러가 `status=PENDING AND next_retry_at ≤ now`인 건을 `(status, next_retry_at)` 인덱스로 가져와 PG 조회 API를 호출한다. 여러 인스턴스가 같은 건을 집지 않도록 행 잠금(`SKIP LOCKED`)을 쓴다.

| PG 조회 결과 | 처리 |
| --- | --- |
| 승인 | `payment` → `APPROVED` + `CHARGE` 기록 + 아웃박스 `RESOLVED` + `PointCharged` |
| 실패·취소 | `payment` → `FAILED` / `CANCELLED`, 충전 없음, 아웃박스 `RESOLVED` |
| 여전히 불명 | `attempt_count + 1`, `next_retry_at`을 지수 백오프로 미룸, `last_checked_at`·`last_error` 갱신 |
| `attempt_count ≥ max_retry` | `FAILED_PERMANENT`. ERROR 로그(`event=PAYMENT_RECHECK_EXHAUSTED`)와 메트릭 `billing.payment.recheck.exhausted`로 알린다. 관리자 재조회 API로 사람이 재확정 |

PG 호출에는 타임아웃과 서킷브레이커를 적용한다. 시작값은 연결 3초, 읽기 10초, 최근 10건 중 50% 실패면 열림, 30초 뒤 반개방이다. 워커의 기본 주기는 1분이고 최초 조회 지연은 1분, 이후 간격은 2·5·10·30분이며 ±10% 지터를 준다. 기본 `max_retry`는 5다. 설정 키는 `billing.toss.*`, `resilience4j.circuitbreaker.instances.tossPayment`, `billing.payment.recheck.*`다.

**2026-10-14 구현 세부:** 워커는 `FOR UPDATE SKIP LOCKED`로 처리할 행을 잡고 `next_retry_at`을 선점 시간(5분) 뒤로 민 채 바로 커밋한다. PG 호출은 트랜잭션 밖에서 하고 결과는 별도 트랜잭션으로 기록한다. 한 건이 실패해도 나머지 건은 계속 처리한다. 백오프는 첫 간격 1분 뒤 2·5·10·30분이고 ±10% 지터를 준다. 결과 매핑은 `APPROVED`(주문·금액 일치) 승인, `FAILED` 실패, `EXPIRED`/`CANCELED` 취소이며 `PENDING`, `NOT_FOUND`, 조회 실패, 불일치는 미확정으로 다음 시각으로 미룬다. 관리자 `:recheck`로 미확정이면 아웃박스의 `last_checked_at`과 `last_error`만 갱신하고 시도 횟수는 올리지 않는다. 메트릭은 `billing.payment.recheck{result=approve|fail|cancel|miss|error}`다. 실행은 `launchcatch.scheduler.enabled`일 때만 한다(배치 프로필).

조회 결과는 `PaymentGatewayInquiryResult.Status`의 `APPROVED`, `FAILED`, `EXPIRED`, `CANCELED`, `PENDING`, `NOT_FOUND`다. 만료와 실패는 서로 다른 값이다. 연결 실패나 서킷 오픈의 `PgNotSentException`, 그 밖의 조회 실패인 `PgUnknownException`은 미확정으로 처리한다.

---

# I-05. 정산 대사 배치 2종

**2026-10-09 수정:** `PG_VS_CHARGE`는 **00:00(전일분) 1회**만 실행한다(13:00 실행은 뺐다). 이 대사는 V51 102·103행에 없는 **설계상 추가 대사**다. `CAMPAIGN_SPEND_VS_LEDGER`는 **00:00(전일분)** 한 번 실행한다 *(2026-10-19 수정, #497: 13:30 당일 대조를 없앴다. 서빙이 하루 종일이라 장중에 카운터를 다시 만들지 않는다. 전일 대조는 서빙 중에 돌지만 대상인 전날에는 00:00 뒤에 차감이 없다)*. `SPEND_VS_IMPRESSION`은 폐기했다. 노출 로그와는 대조하지 않는다.

| `check_type` | 비교 | 자동 복구 |
| --- | --- | --- |
| `PG_VS_CHARGE` | PG 조회 결과(건별) ↔ `payment`(`APPROVED`) + 원장 `CHARGE` (`pg_tid` 기준 1:1). 대상은 전일 생성분 **(2026-10-09 수정)** | 승인인데 `CHARGE`가 없으면 `CHARGE` 보정 기록(`LEDGER_CORRECTION`, `source=RECONCILIATION`) |
| `CAMPAIGN_SPEND_VS_LEDGER` | 당일 소진 카운터 ↔ 원장의 그 캠페인·영업일 `DEDUCT` 합 (원장과 카운터만 비교) | 없음. 그날이 끝나 카운터를 고쳐도 쓰일 곳이 없어 기록만 한다 *(2026-10-19 수정, #497: 13:30 당일 대조의 `CACHE_REBUILD`가 없어졌다)* |
| `CAMPAIGN_OVERSPEND` | 그날 예약액(`budget_daily_plan.daily_budget`) ↔ 원장의 그 캠페인·영업일 `DEDUCT` 합. 합이 예약액을 넘으면 기록한다 *(2026-10-15 추가, #306, V5)* | 없음. 카운터 재구성으로는 보이지 않는 초과 소진(장애 설계 L-2, L-4)이라 사람이 본다 |

**2026-10-16 구현 상태:** `CAMPAIGN_OVERSPEND`는 위 "2종"에 없던 세 번째 `check_type`이다. `CAMPAIGN_SPEND_VS_LEDGER`는 해시를 원장으로 다시 만들면 두 값이 같아져 초과 소진이 보이지 않아서, 한 종류에 두 의미를 담지 않으려고 따로 뒀다. `PG_VS_CHARGE`는 `PgChargeReconciliationScheduler`(작업명 `BILLING_PG_VS_CHARGE_RECON`)가 00:00(Asia/Seoul)에 독립 `@Scheduled`로 돌리며 00:00 묶음에 들어 있지 않다. 비교 규칙은 아래와 같다(#391).

- 우리 쪽 주장은 `payment.status`가 `APPROVED`일 때만 그 금액이고 PG 쪽 주장은 PG 상태가 `APPROVED`일 때만 PG 응답 금액이다. 둘이 같고 0보다 크고 원장에 `CHARGE`가 없으면 `LEDGER_CORRECTION`으로 빠진 `CHARGE`를 기록하고 닫힌 채 남긴다.
- 둘이 다르면(금액이 다르거나 한쪽만 승인으로 보면) 자동 복구 없이 미해결 불일치만 남긴다.
- PG가 `PENDING`이면 아직 결론이 나지 않아 건너뛴다.
- 조회가 실패하면 그 결제만 짧게 1회 재시도하고 그래도 실패하면 불일치로 기록하지 않는다. "불일치"가 아니라 "확인 못 함"이라서 ERROR 로그와 지표만 남긴다.
- 대사 시각 5분 안에 커밋된 결제는 비교에서 뺀다(워터마크).

- 신뢰 순서는 **PG > 과금 판정 > 원장 > 캐시**다. 어긋나면 위쪽 기준으로 아래쪽을 맞춘다.
- **2026-10-09 확정:** `PG_VS_CHARGE`는 PG의 거래 목록 API를 쓰지 않고, **전일(KST) 생성된 `payment` 중 `pg_tid`가 있는 건을 PG 결제 조회 API로 하나씩 조회**해 PG 상태 ↔ `payment.status` ↔ 원장 `CHARGE`를 비교한다. 그래서 **PG에는 있는데 우리 `payment`에 행이 없는 승인은 탐지하지 못한다.** 승인 시도 전에 `pg_tid`를 먼저 커밋하므로(`billing-api-spec.md` 승인 확정 3단계) 정상 흐름에서는 행이 남는다. 그런 승인까지 잡아야 하면 그때 PG 거래 목록 조회를 추가한다.
- 대사 시각 직전 몇 분(권장 5분) 안에 커밋된 행은 비교에서 뺀다(워터마크). 정상 건을 불일치로 오판하지 않기 위해서다.
- 결과는 `settlement_mismatch`에 쌓이고 `job_execution_id`가 운영의 `batch_execution_log.id`를 가리킨다.
- **과과금은 자동 복구하지 않는다.** 관리자가 양수 `ADJUST`(`OVERCHARGE_REVERSAL`)로 정정한다. 서로 다른 `serve_id`가 같은 노출을 중복 과금한 건도 자동 탐지되지 않으며 같은 방식으로 정정한다.
- 같은 `(check_type, target_id, business_date)`는 1건만 기록한다. V2의 UNIQUE를 기준으로 upsert하여 재실행·재탐지로 중복되지 않게 한다.

판정 기록 `BILLED`와 원장 `DEDUCT`는 한 트랜잭션으로 기록하므로 "판정은 과금인데 원장에 차감이 없는" 경우는 생기지 않는다. 그래서 대조는 노출 로그와 판정 기록을 읽지 않고 원장과 카운터만 본다(103행 예외).

---

# I-06. 예산 소진 진행률 조회

98행이 "당일 소진 실시간 카운터와 그날 예약액은 정산이 단독으로 소유한다. 다른 도메인은 자기 카운터를 만들지 않고 **정산이 제공하는 조회 기능으로 읽는다**"고 한다. 광고서빙이 99행의 후보 제외·배분 순서에 쓰는 값이라 HTTP가 아닌 모듈 조회로 연다.

```
billing.contract.BudgetQueryService
  getProgress(campaignIds, businessDate, at)
    -> [ { campaignId, reservedAmount, unitPrice, todaySpend, plannedAmountUntil, progressRate } ]
```

`unitPrice`를 함께 내려주는 것은 광고서빙이 노출 상한(예약액 ÷ 단가 × 1.05)을 계산하기 때문이다(99행).

| 값 | 계산 |
| --- | --- |
| `reservedAmount` | 그날 예약액 = `budget_daily_plan.daily_budget` (= 그 캠페인·영업일 `RESERVE` 합) |
| `todaySpend` | 당일 소진 카운터. **`BILLED`만 센다.** 원본은 원장 `DEDUCT` 합 |
| `plannedAmountUntil` | `budget_hourly_target.target_amount`를 `at` 시각까지 누적. 시간 중간이면 그 시간대 목표를 분 단위로 **비례 안분**한다 |
| `progressRate` | `todaySpend ÷ plannedAmountUntil` |

*(2026-10-19 수정, #497)* 서빙은 하루 종일이고 시간대는 0~23시 24개다. 기본 가중은 `[2, 1, 1, 1, 1, 1, 1, 3, 4, 5, 8, 10, 9, 7, 4, 4, 4, 5, 7, 8, 6, 4, 2, 2]`(합 100)이고 측정값이 아닌 가정이다. 운영 뒤 시간대별 노출 분포로 고친다. 새벽을 0으로 두지 않는다. 0이면 노출 몇 건으로 진행률이 1.2를 넘어 배분에서 빠진다.

예시: 하루 예산 24,000을 24시간 균등 배분하면 11:30까지 계획 합은 11,500이고 5,750을 썼으면 진행률 0.5다. 23:59는 23,983이다.

- **계획 합을 예약액 전체로 고정하는 시각은 없다.** 하루가 끝나야 예약액 전체가 된다(2026-10-19 수정, #497: 13:00 고정을 없앴다).
- **시간대별 목표의 합은 그날 예약액과 같다.** 가중치는 비율로만 쓴다. 가중치 합으로 나눠 내림하고 남는 금액을 마지막으로 가중치가 있는 시간대에 얹어 합을 예약액과 맞춘다. 그래서 설정의 합이 100이 아니어도 거부하지 않는다. 설정 검증은 개수 24, 음수 없음, 합 0 초과만 본다(2026-10-19 수정, #497).
- **진행률을 1.0으로 자르지 않는다.** 계획보다 많이 쓴 캠페인은 1.2를 그대로 반환하고, 1.2 초과 판정은 광고서빙이 한다.
- 소비 기준(1.0 미만 우선, 1.2 초과 시 배분 슬롯 제외, 신규 캠페인 ×0.8)은 광고서빙이 갖는다. 정산은 숫자만 준다(`비즈니스 규칙` 30·34·35행).
- 서빙 핫패스에서 불리므로 원장 `SUM`이 아니라 카운터와 `budget_hourly_target`만 읽는다.
- **계획 합이 0일 때 진행률은 `0`이다.** V51 `비즈니스 규칙` 34행이 "하루 시작 시점에는 모든 캠페인의 진행률이 0으로 같아 등록이 빠른 캠페인이 먼저 배정되지만, 노출이 시작되면 진행률이 갈라지므로 영향은 하루 첫 회차에 한정된다"고 적어 하루 시작(00:00) 정각의 값을 정했다. 모든 캠페인이 같은 `0`이므로 공정성은 동률 처리(캠페인 등록 시각 오름차순)가 맡는다. **그날 예약액이 0인 캠페인은 `PAUSED(NO_POINTS)`라 그날 피드 후보에서 빠지므로**(`검증 시나리오` 27행, 96행 스냅샷) "포인트 없는 캠페인이 1순위로 올라온다"는 상황은 생기지 않는다. 그래도 조회가 들어오면 같은 규칙으로 `0`을 돌려준다.

---

# I-07. 캠페인 상태 변경 수신

저장소 `docs/architecture/런치캐치_도메인_구조와_의존성_설계.md` 2.4의 이벤트 표가 `CampaignStatusChangedEvent`를 **campaign → billing**으로 두고, 정산이 받아서 할 일을 "**과금 판정의 중단 여부 변경과 재개 시 시간대별 목표 소진 재계산**"으로 적는다.

```
수신: campaign.contract.CampaignStatusChangedEvent { campaignId, businessDate, servable, changedAt }
자리: billing 내부 listener/. 발행자와 같은 트랜잭션(@EventListener)
```

1. `changedAt`이 저장된 값보다 **늦을 때만** 반영한다. 2.4의 "수신은 중복과 순서에 상관없이 같은 결과가 나오게 작성한다" 규칙이고, 같은 문단이 "**정산은 중단 표시와 함께 시각을 저장**"이라고 요구한다.
2. 중단으로 바뀌면 그날 차감 경로가 `NOT_BILLED_PAUSED`를 판정한다(103행 "중단 이후 도착"). 이미 내려간 카드의 노출이 serve 기록 TTL 30분 동안 들어오므로 **장중 전이 뒤 최대 30분간 미과금 노출이 발생한다.** 이 노출은 점주에게 과금하지 않는다.
3. 재개로 바뀌면 `budget_hourly_target`을 다시 계산한다(48행). **지난 시간대는 그대로 두고 남은 시간대만** 새 예약액 기준으로 갱신한다.

## 중단 상태의 저장과 구현 범위

`budget_daily_plan.paused`와 `status_changed_at`은 V2 마이그레이션에 반영돼 있다. `BudgetPlanStatusService`는 DB의 중단 상태를 발행자와 같은 트랜잭션에서 바꾸고 커밋 뒤 Valkey에 반영한다.

- 캠페인×영업일 행이 00:00에 이미 만들어지고(`ACTIVE`·`PAUSED(OWNER)`·`PAUSED(NO_POINTS)` 대상), `NOT_BILLED_PAUSED` 판정이 필요한 범위와 정확히 겹친다.
- 차감 핫패스가 같은 행에서 `unit_price`·`owner_id`를 이미 읽으므로 **조회가 늘지 않는다.**
- `deduction_result`에는 둘 수 없다 — `serve_id` 단위 판정 기록이라 캠페인 단위 상태를 담는 자리가 아니다.
- 이 컬럼은 장중에 갱신된다. `daily_budget`도 충전 재예약 때 늘어나므로 계획 행 전체를 읽기 전용 스냅샷으로 보지 않는다.
- 대안은 별도 `campaign_pause_state`(campaign_id PK) 테이블이다. 영업일과 무관한 현재 상태를 들고 싶을 때 맞지만, 조회가 한 번 늘고 캠페인 상태를 정산이 복제 보관하는 모양이 되어 소유 원칙과 헷갈린다.

반영된 DDL은 billing 마이그레이션 `V2__billing_pause_mismatch_date_and_indexes.sql`에 있다.

위 3번의 재개 시 목표 재계산은 설계 요구이며 현재 상태 리스너는 수행하지 않는다. `HourlyTargetPlanner`는 00:00에 목표를 만들고 기존 목표가 있으면 건너뛴다. `HourlyTargetService`도 목표를 하루 동안 캐시한다. 따라서 재개·충전 재예약 후 남은 목표를 다시 계산하는 처리와 캐시 갱신은 후속 구현이 필요하다.

---

# I-08. 분석 집계 조회

*(2026-10-16 추가, #352, #402)* 분석의 일별 집계 배치가 영업일마다 부르는 읽기 전용 조회다. HTTP가 아니라 모듈 조회이고 집계 SQL은 정산이 소유한다. 분석은 합계와 건수만 받고 소진액(`DEDUCT` 합 − 양수 `ADJUST` 합)은 분석이 계산한다. 영업일에 귀속하는 기준과 정정 대상 영업일의 규칙은 [구조와 동작 설계 5장](./billing_구조와_동작_설계.md#집계-조회의-영업일-귀속)이 갖는다.

```
billing.contract.BillingStatsQueryService
  ledgerSums(businessDate)             -> [ { ownerId, campaignId, 유형별 합계 } ]
  billingCounts(businessDate)          -> [ { campaignId, BILLED, NOT_BILLED_BUDGET, NOT_BILLED_PAUSED, NOT_BILLED_NO_PLAN 건수 } ]
  dailyBudgets(businessDate)           -> [ { campaignId, dailyBudget, unitPrice } ]
  dailyTotals(businessDate)            -> { unspentBalance, ledgerSum, mismatchCount }
  correctionTargetDates(recordedDate)  -> [ 정정 대상 영업일 ]
```

| 메서드 | 분석이 쓰는 곳 |
|---|---|
| `ledgerSums` | 매출, 점주별 소진, 캠페인 리포트 |
| `billingCounts` | 캠페인 리포트, 미과금 지표 |
| `dailyBudgets` | 캠페인 리포트 |
| `dailyTotals` | 플랫폼 현황(그날 말 미소진 잔액, 원장 대조, 불일치 건수) |
| `correctionTargetDates` | 재집계 대상 찾기. 그날 기록된 양수 정정이 가리키는 과거 영업일을 `aggregation_pending_date`(`ADJUSTED`)에 남긴다 |

- 미소진 잔액은 `CHARGE + REFUND + ADJUST + DEDUCT`의 누적 합이다. 점주 잔액에 예약 중 포인트를 더한 값과 같다. 원장 대조용 합계는 그날 기록된 같은 네 유형의 합이라 전날 말 값에 더하면 그날 말 값이 된다.
- 과금 정합성 검증식(`검증 기준`의 첫 행)은 이 조회의 `ledgerSums`와 `billingCounts`, `dailyBudgets`로 확인한다. 캠페인별 `DEDUCT` 합 = `BILLED` 수 × 그날 단가다.
- 쿼리 비용과 인덱스 후보는 [성능 개선 3장](./billing_성능_개선.md)이 갖는다.

---

# 검증 기준

| 항목 | 기준 |
| --- | --- |
| 과금 원장 정합성 | 캠페인·영업일별 과금 노출 수 × 적용 단가 = `DEDUCT` 합의 절댓값이다. 과오 차감 정정은 원래 영업일의 광고 비용에서 별도로 뺀다 |
| 정정 뒤 광고 비용 | `DEDUCT` 합의 절댓값 − 같은 대상 영업일의 양수 `ADJUST` 합이다. 정정으로 과금 노출 수 자체를 줄이지 않는다 |
| 해제 정합성 | 캠페인·영업일별 `RELEASE` = `RESERVE` − `DEDUCT` |
| 상한 | `DEDUCT` 합 ≤ 그날 예약액. 예약액 초과 차감 0건, 잔액 음수 0건 |
| 멱등 | 같은 `serve_id`의 `DEDUCT` 1건, 판정 기록 1건, 카운터 증가 1회 |
| 성능 | 노출 차감 초당 10,000건(비기능 17행) |

상세 시나리오는 요구사항 명세서의 `검증 시나리오` 시트를 따른다.

---

# 아직 정해지지 않은 것

| 항목 | 현재 기준 |
| --- | --- |
| ~~서빙 시간대 종료 시각~~ | *(2026-10-19 닫음, #497)* 서빙 시간대를 없애 `미결정 사항` #23(종료 시각)과 #22(노출 인정 시간)가 이 설계에서 쓰이지 않는다. 해제는 다음 날 00:00이고 노출 인정 30분은 광고 서빙의 serve TTL이 지킨다 |
| **카운터 수동 재구성 경로** | 98행 예외가 "장애 직후에는 수동으로 실행한다"고 요구하는데 경로가 없다. 운영의 실패 작업 수동 재실행(102행)으로 처리할지, 정산이 관리자 API를 둘지 |
| 재충전 재예약 시 목표 재계산 | 48행(재개)은 재계산이 명시돼 있으나 49행(재충전) 자체의 목표 재계산 여부는 명세에 없다 |
| 00:00 `RELEASE`의 재시도 정책 | *(2026-10-19 수정, #497: 00:00 해제가 전날의 정식 해제가 되었다)* 전날 해제를 00:00이 시도하는데, 이마저 실패할 경우의 재시도 횟수·알림 정책이 없다 **2026-10-12 수정: 정했다.** 운영 스케줄러의 일시 오류 자동 재실행(최대 2회)에 맡기고, 그래도 실패하면 `FAILED`, 알림, 대사 불일치 기록으로 끝낸다. |
| 102행 (5) 문구 | "예약해 **캠페인에 넘긴다**"가 51행·98행("정산이 가진다, 캠페인은 보관하지 않는다")과 충돌한다. 명세 정정 요청 필요 |
