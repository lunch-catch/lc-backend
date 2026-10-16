# 결제·포인트·정산 (billing) 배치와 내부 흐름 설계

**2026-10-12 분리:** 원래 `billing(예약과 차감 배치).md`의 이름만 바꿨다. 내용은 그대로다.

HTTP가 아니라 **모듈 간 호출·이벤트·배치**다. 점주·관리자 API는 `billing-api-spec.md`에서 다룬다.

| 근거 | 기준 |
| --- | --- |
| 요구사항 | V51 **98행**(하루 예산 배분과 예산 소진 진행률), **103행**(노출 차감 및 원장 기록), **51행**(잔액 부족 시 게재 중단), **49행**(충전 후 재예약) |
| 참조만 하는 행 | 캠페인 47·48행, 광고 서빙 99·100행, 운영 102행(스케줄러)·104행(감사 로그) |
| 저장 구조 | `billing/V1__billing_init.sql`의 `budget_daily_plan`, `budget_hourly_target`, `deduction_result`, `point_ledger` |
| 도메인 규칙 | 저장소 `docs/architecture/런치캐치_도메인_구조와_의존성_설계.md` 2.2(조회 인터페이스), 2.4(명령 인터페이스와 이벤트) |

**저장 구조가 요구사항과 다르면 마이그레이션을 따른다.** 문서 폴더의 `lunchcatch-erd-v4_1.sql`은 부분본·구버전이라 기준이 아니고, 마이그레이션 `README.md`가 원본이라고 적은 드라이브의 `전체_flyway.sql`도 사람이 손으로 맞추는 사본이라 무조건 믿지 않는다.

---

## 목록

| # | 이름 | 행 | 성격 |
| --- | --- | --- | --- |
| I-01 | 노출 차감 → 판정 기록·원장 기록 | 103 (참조 99·100) | 광고서빙이 `billing.contract.PointDeductor` 직접 호출 |
| I-02 | 00:00 / 13:30 예약·해제 작업 | 98, 102 (3)(4)(5)(7), 51 | 운영 스케줄러에 등록하는 배치 *(2026-10-12 수정: 00:00 작업만 운영 스케줄러에 등록한다. 13:30 작업은 정산의 `@Scheduled`와 `BatchExecutionService.claim`으로 돌린다. 구현 계획 D-6)* |
| I-03 | 충전 직후 재예약 | 48, 49, 51 | billing 내부 `PointCharged` 핸들러 (2026-10-12 수정: 이름에서 `Event`를 뺐다) |
| I-04 | 결제 재조회 워커(아웃박스) | 22, 49 | 주기 스케줄러 |
| I-05 | 정산 대사 배치 2종 | 102, 103 | 배치 |
| I-06 | 예산 소진 진행률 조회 | 98 | 광고서빙이 `billing.contract.BudgetQueryService` 호출 |
| I-07 | 캠페인 상태 변경 수신 | 51, 103 | `campaign.contract.CampaignStatusChangedEvent` 리스너 |

### 정산이 쓰는 다른 도메인의 공개 기능

| 방향 | 인터페이스 | 무엇 |
| --- | --- | --- |
| billing → campaign | `campaign.contract.CampaignQueryService` | 00:00 예약 대상과 다음 영업일 적용 하루 예산, 충전 재예약 대상(`PAUSED(NO_POINTS)` 캠페인), 환불 거부 조건의 집행 중 캠페인 |
| billing → campaign | `campaign.contract.CampaignFundingUpdater` | 예약 결과와 판정 반영. 잔액 부족이면 `PAUSED(NO_POINTS)`, 재개 판정이면 `ACTIVE`로 **상태만** 바꾼다 |
| billing → campaign | `campaign.contract.CampaignQueryService.findAllById` | **2026-10-14:** 포인트 조정이 닫으려는 `CAMPAIGN_SPEND_VS_LEDGER` 대사 건의 캠페인 점주 확인(`billing-api-design.md` 「포인트 조정」) |
| billing → owner | `owner.contract.OwnerStatusQuery.findStatus` | **2026-10-14:** 포인트 조정 대상 점주가 있는지 확인(구현 계획 N-2, G-4). 새 간선이라 레포 설계 문서 2.2절과 CODEOWNERS를 같은 PR에서 고친다 |
| billing → ops | 운영의 설정값 조회 기능 | 노출 단가(아래 I-02 참고) |
| adserving → billing | `billing.contract.PointDeductor` | I-01 |
| adserving → billing | `billing.contract.BudgetQueryService` | I-06 |
| campaign → billing | `CampaignStatusChangedEvent` | I-07 |

**정산은 도메인 간 이벤트를 발행하지 않는다.** `PointCharged`(I-03)는 billing 내부 통보다. **2026-10-12 수정:** 저장소 아키텍처 테스트가 `Event`로 끝나는 타입을 발행 도메인의 `contract`에 두라고 요구하므로 공개하지 않는 이 통보는 이름에서 `Event`를 뺐다(구현 계획 D-2).

**2026-10-14 수정:** 아래 요청은 #204(2026-10-13 병합)로 develop에 들어왔다. `hasLiveCampaign`, 일괄 조회 `findOwnerIdsWithLiveCampaign`, `findByOwnerIdAndStatusIn`, `CampaignFundingUpdater.applyFundingJudgement`가 실제 구현과 함께 있다. 00:00 예약 대상은 따로 만들지 않고 광고 서빙과 같은 `findServableOn(businessDate)`를 쓴다. 운영의 `PlatformSettingQueryService.getImpressionUnitPrice()`도 들어왔다. 아래 표는 요청 당시의 기록이다.

**2026-10-09: campaign에 요청하는 계약(제안, campaign 담당자와 확정 필요).** 현재 코드의 `CampaignQueryService`는 `findByStoreIdIn`·`findAllById`뿐이고 `CampaignFundingUpdater`는 메서드가 없는 빈 인터페이스라 아래가 필요하다.

| 인터페이스 | 요청 메서드(가칭) | 쓰는 곳 |
| --- | --- | --- |
| `CampaignQueryService` | `findForReservation(businessDate)` — 집행 기간 안에 `businessDate`가 들어가는 `ACTIVE`·`PAUSED(OWNER)`·`PAUSED(NO_POINTS)` 캠페인 전체(점주 ID 포함) | 00:00 예약 배치(전 점주 대상이라 점주별 조회로는 부족하다) |
| `CampaignQueryService` | `findByOwnerIdAndStatusIn(ownerId, statuses)` | I-03의 `PAUSED(NO_POINTS)` 조회 |
| `CampaignQueryService` | `hasLiveCampaign(ownerId)` — `ACTIVE`·`SCHEDULED`·`PAUSED` 전체를 집행 중으로 본다 | 환불 거부(`BILLING-010`) |
| `CampaignInfo` | 그날 적용할 하루 예산(점주가 바꾼 값이 예약돼 있으면 그 값)과 `PAUSED(OWNER)`·`PAUSED(ADMIN)`·`PAUSED(NO_POINTS)` 사유를 구분할 수 있는 상태 | 00:00 예약액 계산, 재개 대상 판단 |
| `CampaignFundingUpdater` | 위 "판정 통보"의 필드를 받는 단일 메서드(가칭 `applyFundingJudgement`) — `judgement`가 `NO_POINTS`면 `PAUSED(NO_POINTS)`, `RESUME`이면 `ACTIVE`로 상태만 바꾼다 | 00:00 판정, I-03 재개 |

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

1. 원자 연산으로 `당일 소진 + 단가 ≤ 그날 적용 하루 예산(예약액)`을 검사한다. 검사와 차감은 나눌 수 없는 하나의 동작이다.
2. 판정을 `deduction_result`(`UNIQUE(serve_id)`)에 기록한다.

| 판정 | 언제 |
| --- | --- |
| `BILLED` | 검사 통과 |
| `NOT_BILLED_BUDGET` | 그날 예약액에 도달 |
| `NOT_BILLED_PAUSED` | 캠페인이 중단된 뒤 도착 (I-07의 중단 표시를 본다) |

`NOT_BILLED_SOLD_OUT`은 차감을 부르지 않으므로 여기 없다. 광고서빙이 `impression_log.sold_out`에 기록하고 리포트가 `serve_id`로 조인한다.

3. `BILLED`이면 `point_ledger`에 `DEDUCT(amount = -unit_price, serve_id, campaign_id, owner_id, source=REALTIME)`를 기록한다. `owner_id`는 `budget_daily_plan.owner_id`다.
4. 멱등: `UNIQUE(serve_id)`와 `UNIQUE(serve_id, entry_type)` 위반은 **이미 처리된 건으로 보고 성공 처리**하며, 판정 기록 1건과 카운터 증가 1회만 남는다.
5. 예약액에 도달하면 **차감만 중단한다.** 노출은 노출 상한(예약액 ÷ 단가 × 1.05, 광고서빙 99행)까지 계속되고 캠페인 상태는 바뀌지 않는다.
6. DB 쓰기가 실패하면 예외로 트랜잭션 전체를 롤백한다(노출 저장도 롤백). 별도 `ledger_outbox`는 두지 않는다. 롤백되면 정산이 곧바로 카운터의 처리 표시와 소진을 되돌린다(2026-10-14, 구현 순서 F-6). 되돌리기가 실패해 카운터만 증가한 채 남은 경우는 I-05의 카운터 대조가 복구한다.
7. **이 경로는 잔액을 읽지 않는다.** 그날 예약액이 이미 잔액에서 빠져 있어 잔액은 음수가 될 수 없다.

성능 목표는 초당 10,000건이다(비기능 17행).

---

# I-02. 00:00 / 13:30 예약·해제 작업

운영 도메인 스케줄러(102행)에 등록하는 작업이다. *(2026-10-12 수정: 00:00 작업만 그렇다. 단계 `@Order`는 102행 단계 번호 × 10이다. 13:30 작업은 운영 스케줄러가 00:00 묶음만 돌리므로 정산의 `@Scheduled`가 `BatchExecutionService.claim`으로 점유해 돌리고, 실패하면 다음 00:00 보정 `RELEASE`가 이어받는다. 구현 계획 D-6)* 실행 이력은 운영의 `batch_execution_log`에 `UNIQUE(job_name, business_date)`로 남는다.

00:00 작업은 순서 의존이 있어 **앞 단계가 실패하면 뒤 단계를 실행하지 않고 중단**하며, 실패한 작업명과 사유를 기록해 관리자에게 알리고 **실패한 단계만 수동으로 재실행**할 수 있다. 배치 서버는 2대이고, 실행 중인 서버의 생존 신호(`updated_at`, 30초 주기)가 2분 넘게 끊기면 다른 서버가 이어받아 **끝나지 않은 단계부터** 다시 실행한다(`owner_id`가 이어받은 서버로 바뀐다). 일시적 오류(교착, 락 대기 초과, 연결 오류, Valkey 시간 초과)는 30초·1분 간격으로 최대 2회 자동 재실행한다(`retry_count`).

## 00:00 작업

정산이 구현하는 단계는 (3)(4)(5)(7)이고, (6)은 정산이 판정해 캠페인이 상태에 반영한다. 나머지는 다른 도메인 소유다.

| 단계 | 작업 | 내용 |
| --- | --- | --- |
| (3) | 예산 초기화 | 당일 소진 카운터·예약액 키 초기화. 점주가 바꿔 둔 하루 예산 설정값이 있으면 이때 적용한다(98행, 45행) |
| (4) | 전일 카운터↔원장 대조 | `CAMPAIGN_SPEND_VS_LEDGER`(I-05) |
| (5) | 예약 | ① 전날 13:30 `RELEASE`가 실패해 남은 캠페인만 보정 `RELEASE` ② 집행 기간 안의 `ACTIVE`·`PAUSED(OWNER)`·`PAUSED(NO_POINTS)` 캠페인마다 `min(설정 하루 예산, 잔액)`을 `RESERVE(−)`로 기록하고 `budget_daily_plan`(`owner_id`, `unit_price`, `daily_budget` = 그날 예약액)을 만든 뒤 예약액 카운터를 세팅. **2026-10-12 수정: 잔액이 모든 캠페인 합보다 작으면 캠페인 ID 오름차순으로 앞에서부터 채운다**(구현 계획 D-4, V51 34행의 동률 기준과 같은 순서). 잔액이 0이 된 뒤의 캠페인은 예약액 0으로 계획 행만 만들고 (6)에서 `NO_POINTS`로 판정된다 |
| (6) | 잔액 부족 판정 | 예약액 < 단가이면 `NO_POINTS`. **대상은 `ACTIVE`와 `PAUSED(NO_POINTS)`만이다** — `PAUSED(OWNER)`는 점주의 재개 요청 때 48행 규칙으로 판단한다 |
| (7) | 시간대별 목표 계산 | `budget_hourly_target`에 10·11·12시 `target_amount`(기본 가중 34/33/33). **대상은 (6)까지 확정된 `ACTIVE`만이다.** `UNIQUE(budget_daily_plan_id, hour)`로 재실행 멱등. 일별 노출 목표 = 그날 적용 하루 예산 ÷ 단가 |

## 13:30 작업

| 단계 | 작업 |
| --- | --- |
| (1) | 당일 카운터↔원장 대조 — `CAMPAIGN_SPEND_VS_LEDGER`(I-05) |
| (2) | 그날 `RELEASE` — 캠페인별 (그날 `RESERVE` 합 − 그날 `DEDUCT` 합)이 양수이면 `RELEASE(+)` 기록 |

대조가 `RELEASE`의 선행 조건이다(해제액을 원장의 차감 합계로 계산하기 때문). 13:30인 이유는 서빙이 12:59에 끝나도 이미 내려간 카드의 노출이 노출 인정 시간 30분 동안 들어오기 때문이다 — 그 창이 끝난 뒤라야 처리 중인 차감이 없고 늦게 온 노출의 차감분도 해제액에서 빠진다.

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
- `setting_value`는 `VARCHAR(500)`이므로 정수 문자열로 저장하고 **파싱은 운영의 조회 기능이 맡는다**(`getInt` 형태). 요청하는 시그니처는 전용 메서드를 1순위로 한다 — `int getImpressionUnitPrice()`, 차선은 `int getInt(String key)`. **2026-10-09 확정:** 전용 메서드로 요청한다(`campaign·운영 계약 요청.md`, 운영 회신 대기). 단가 **값**은 팀장 결정(미결정 사항 #2) 전까지 개발 환경에 기본값 10을 넣는다. 둘 다 키가 없거나 정수로 파싱되지 않으면 **예외를 던진다.** `getInt(key, defaultValue)`처럼 기본값으로 떨어지는 오버로드는 쓰지 않는다.
- **`setting_key`에 CHECK를 걸지 않는다.** 마이그레이션 `README.md`가 "값이 자주 늘어나는 열에 CHECK를 걸면 값 하나를 더할 때마다 테이블 복사가 일어난다"고 경고한다. 그래서 **오타를 DB가 막아주지 않는다** — 키 이름이 어긋나면 조회가 빈 결과를 돌려주고 조용히 실패한다.
- **그래서 키를 못 찾으면 예약 작업을 중단한다.** 0이나 `null`로 진행하면 예약액이 전부 0이 되어 모든 캠페인이 `NO_POINTS`로 전이한다. 예외를 던져 `batch_execution_log`에 `FAILED`와 사유를 남기고 뒤 단계를 실행하지 않는다. 단가가 0 이하이거나 정수로 파싱되지 않는 경우도 같다.
- **단가의 "익일 적용"은 별도 장치가 필요 없다.** `platform_setting`에 `effective_date`가 없지만, 00:00 배치가 그 시점의 현재 값을 읽어 `budget_daily_plan.unit_price`에 스냅샷하므로 오늘 바꾼 값이 내일부터 적용된다(`비즈니스 규칙` 11행). 점주 하루 예산이 (3)단계에서 적용되는 것과 같은 메커니즘이다.

## 그 밖의 규칙

- `RELEASE`와 정기 `RESERVE`는 `payment_id`가 `NULL`이고, 멱등은 생성 컬럼 `day_reserve_dedup_key`(캠페인×영업일×유형) UNIQUE가 맡는다.
- `RESERVE` 계산의 잔액은 전날 `RELEASE`(00:00 보정분 포함) 이후 원장에서 계산한 잔액(`DEDUCT` 제외)이다. **이월은 없다.**
- 예약액이 `0`이면 원장에 기록하지 않고(`RESERVE`는 음수만 허용) `budget_daily_plan.daily_budget = 0`으로 두고 판정만 통보한다. V51 102행 (5)가 "예약액이 0이어도 넘긴다"고 요구한다. **DB CHECK가 아직 `daily_budget > 0`이라 `>= 0`으로 완화해야 한다.**
- `RESERVE`·`RELEASE`는 `source=RECONCILIATION`으로 기록한다(배치 컨텍스트).
- 당일 소진 카운터에 장애가 나면 원장으로 재구성한다. 재구성은 대조 작업이 계산해 정산이 자기 카운터에 직접 반영하고, 장애 직후에는 **수동으로 실행**한다(98행 예외) — 경로는 아래 "아직 정해지지 않은 것" 참고.

---

# I-03. 충전 직후 재예약

승인 확정, 웹훅, 재조회 워커 어디서든 `CHARGE`가 확정되면 커밋 후 발행하는 billing 내부 이벤트다.

**2026-10-09 수정:** `CHARGE` 행이 **새로 만들어진** 커밋 뒤에는 경로와 관계없이 발행한다. 승인 확정(`POST /v1/owner/payments/{paymentId}:confirm`), 웹훅, 재조회 워커가 승인 확정 로직을 하나의 서비스 메서드로 공유하고 그 안에서 발행한다. 이미 `APPROVED`라 `(payment_id, CHARGE)` UNIQUE에 걸려 새 행이 없으면 발행하지 않는다. **`LEDGER_CORRECTION`이 만든 `CHARGE` 보정은 과거 영업일 값을 고치는 것이므로 발행하지 않는다.**

```
PointCharged { ownerId, paymentId, amount }
```

1. `CampaignQueryService`로 이 점주의 집행 기간 안 `PAUSED(NO_POINTS)` 캠페인이 있는지 묻는다. 없으면 끝.
2. **서빙 시간대(10:00~12:59)가 지났으면(13:00 이후) 다시 잡지 않는다.** 다음 00:00 예약이 반영한다(51행).
3. `새 예약액 = min(설정 하루 예산, 기존 예약액 + 잔액)`. 기존 예약액은 원장의 오늘 `RESERVE` 합(= `budget_daily_plan.daily_budget`)에서 구한다.
4. 늘어난 만큼만 `RESERVE(-delta, payment_id)`를 기록하고 `budget_daily_plan.daily_budget`과 예약액 카운터를 새 예약액으로 갱신한다. 멱등은 `UNIQUE(payment_id, entry_type)`로, 같은 결제로 재예약은 1회뿐이다.
5. **재개 판정은 정산이 한다**(새 예약액 ≥ 단가). `CampaignFundingUpdater`에 `cause=RECHARGE`로 통보하면 캠페인이 상태만 `ACTIVE`로 바꾼다. 관리자가 중단한 캠페인(`PAUSED(ADMIN)`)과 집행 기간이 끝난 캠페인은 재개하지 않는다(51행 예외).

재개된 캠페인의 시간대별 목표 재계산은 I-07이 맡는다(48행).

---

# I-04. 결제 재조회 워커 — 아웃박스

`payment_reconciliation_outbox`는 PG 응답이 불명확한 결제(`UNKNOWN`)를 확정하기 위한 재시도 큐다.

- **적재**: 승인 확정에서 결제를 `UNKNOWN`으로 만들 때 *(2026-10-08 수정: 웹훅은 UNKNOWN을 만들지 않는다 — `billing-api-design.md` 「서버가 하는 일」의 웹훅 절)* 같은 트랜잭션에서 `status=PENDING`, `attempt_count=0`, `max_retry`, `next_retry_at`을 채워 넣는다. 결제당 1행(`UNIQUE(payment_id)`).
- **처리**: 주기 스케줄러가 `status=PENDING AND next_retry_at ≤ now`인 건을 `(status, next_retry_at)` 인덱스로 가져와 PG 조회 API를 호출한다. 여러 인스턴스가 같은 건을 집지 않도록 행 잠금(`SKIP LOCKED`)을 쓴다.

| PG 조회 결과 | 처리 |
| --- | --- |
| 승인 | `payment` → `APPROVED` + `CHARGE` 기록 + 아웃박스 `RESOLVED` + `PointCharged` |
| 실패·취소 | `payment` → `FAILED` / `CANCELLED`, 충전 없음, 아웃박스 `RESOLVED` |
| 여전히 불명 | `attempt_count + 1`, `next_retry_at`을 지수 백오프로 미룸, `last_checked_at`·`last_error` 갱신 |
| `attempt_count ≥ max_retry` | `FAILED_PERMANENT`. ERROR 로그(`event=PAYMENT_RECHECK_EXHAUSTED`)와 메트릭 `billing.payment.recheck.exhausted`로 알린다. 관리자 재조회 API로 사람이 재확정 |

PG 호출에는 타임아웃과 서킷브레이커를 적용한다. **2026-10-10 수정:** 시작값은 연결 3초, 읽기 10초, 최근 10건 중 50% 실패면 열림, 30초 뒤 반개방이다(`billing.toss.*`, `resilience4j...tossPayment`로 조정). 백오프 간격과 `max_retry` 값은 미정이다. *(2026-10-12 수정: 정했다. 주기 1분, 백오프 1·2·5·10·30분(지터), `max_retry` 5(약 48분, 2026-10-14 개정). 구현 계획 D-10)*

**2026-10-14 구현 세부:** 워커는 `FOR UPDATE SKIP LOCKED`로 처리할 행을 잡고 `next_retry_at`을 선점 시간(5분) 뒤로 민 채 바로 커밋한다. PG 호출은 트랜잭션 밖에서 하고 결과는 별도 트랜잭션으로 기록한다. 한 건이 실패해도 나머지 건은 계속 처리한다. 백오프는 첫 간격 1분 뒤 2·5·10·30분이고 ±10% 지터를 준다. 결과 매핑은 `APPROVED`(주문·금액 일치) 승인, `FAILED` 실패, `EXPIRED`/`CANCELED` 취소이며 `PENDING`, `NOT_FOUND`, 조회 실패, 불일치는 미확정으로 다음 시각으로 미룬다. 관리자 `:recheck`로 미확정이면 아웃박스의 `last_checked_at`과 `last_error`만 갱신하고 시도 횟수는 올리지 않는다. 메트릭은 `billing.payment.recheck{result=approve|fail|cancel|miss|error}`다. 실행은 `launchcatch.scheduler.enabled`일 때만 한다(배치 프로필).

**2026-10-10 수정:** 조회 결과는 `PaymentGatewayInquiryResult.Status`로 돌아온다. `APPROVED`(승인), `FAILED`(실패·만료), `CANCELED`(승인 뒤 PG 취소, 부분 취소 포함), `PENDING`(여전히 불명, 처음 보는 PG 상태 포함), `NOT_FOUND`(PG에 결제 없음). 위 표의 "여전히 불명"이 `PENDING`이다. `CANCELED`와 `NOT_FOUND`를 받았을 때의 처리는 결제 프로세스(C)에서 정하며 아직 미정이다. *(2026-10-12 수정: 승인 확정(C1)에서 정했다. 동기 재조회에서 `CANCELED`는 우리 쪽이 승인한 적이 없으므로 충전 없이 `CANCELLED`로 닫고, `NOT_FOUND`는 승인 요청이 닿지 않았을 수 있어 `UNKNOWN`으로 둔다. 워커도 같은 규칙을 따른다. 구현 계획 4.2)* 조회가 연결 실패나 서킷 오픈이면 `PgNotSentException`, 그 밖의 실패면 `PgUnknownException`이 나므로 "여전히 불명"과 같이 다음 주기에 다시 조회한다.

---

# I-05. 정산 대사 배치 2종

**2026-10-09 수정:** `PG_VS_CHARGE`는 **00:00(전일분) 1회**만 실행한다(13:00 실행은 뺐다). 이 대사는 V51 102·103행에 없는 **설계상 추가 대사**다. `CAMPAIGN_SPEND_VS_LEDGER`는 **13:30과 00:00(전일분)** 두 번 실행한다. 13:30 대조는 같은 배치 안 `RELEASE`의 선행 조건이고, 00:00 대조는 전일 최종 확인이다. **서빙 시간대(10:00~12:59) 중에는 어느 체크도 돌리지 않는다.** `SPEND_VS_IMPRESSION`은 폐기했다 — 노출 로그와는 대조하지 않는다.

| `check_type` | 비교 | 자동 복구 |
| --- | --- | --- |
| `PG_VS_CHARGE` | PG 조회 결과(건별) ↔ `payment`(`APPROVED`) + 원장 `CHARGE` (`pg_tid` 기준 1:1). 대상은 전일 생성분 **(2026-10-09 수정)** | 승인인데 `CHARGE`가 없으면 `CHARGE` 보정 기록(`LEDGER_CORRECTION`, `source=RECONCILIATION`) |
| `CAMPAIGN_SPEND_VS_LEDGER` | 당일 소진 카운터 ↔ 원장의 그 캠페인·영업일 `DEDUCT` 합 (원장과 카운터만 비교) | 정산이 카운터를 원장 기준으로 재구성(`CACHE_REBUILD`) |

- 신뢰 순서는 **PG > 과금 판정 > 원장 > 캐시**다. 어긋나면 위쪽 기준으로 아래쪽을 맞춘다.
- **2026-10-09 확정:** `PG_VS_CHARGE`는 PG의 거래 목록 API를 쓰지 않고, **전일(KST) 생성된 `payment` 중 `pg_tid`가 있는 건을 PG 결제 조회 API로 하나씩 조회**해 PG 상태 ↔ `payment.status` ↔ 원장 `CHARGE`를 비교한다. 그래서 **PG에는 있는데 우리 `payment`에 행이 없는 승인은 탐지하지 못한다.** 승인 시도 전에 `pg_tid`를 먼저 커밋하므로(`billing-api-spec.md` 승인 확정 3단계) 정상 흐름에서는 행이 남는다. 그런 승인까지 잡아야 하면 그때 PG 거래 목록 조회를 추가한다.
- 대사 시각 직전 몇 분(권장 5분) 안에 커밋된 행은 비교에서 뺀다(워터마크). 정상 건을 불일치로 오판하지 않기 위해서다.
- 결과는 `settlement_mismatch`에 쌓이고 `job_execution_id`가 운영의 `batch_execution_log.id`를 가리킨다.
- **과과금은 자동 복구하지 않는다.** 관리자가 양수 `ADJUST`(`OVERCHARGE_REVERSAL`)로 정정한다. 서로 다른 `serve_id`가 같은 노출을 중복 과금한 건도 자동 탐지되지 않으며 같은 방식으로 정정한다.
- 같은 `(check_type, target_id, business_date)`는 1건만 기록해야 재실행·재탐지로 중복되지 않는다. **스키마에 `business_date`와 그 UNIQUE가 아직 없다.** *(2026-10-12 수정: V2가 `business_date`와 `UNIQUE(check_type, target_id, business_date)`를 더했다)*

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

98행 예시: 하루 예산 10,000을 10·11·12시 균등 배분하면 11:30까지 계획 합은 5,000이고 2,000을 썼으면 진행률 0.4다. 기본 가중 34/33/33이면 같은 시점 계획 합이 3,400 + 3,300 × 0.5 = 5,050이라 약 0.396이다.

- **13:00 이후에는 계획 합을 그날 예약액 전체로 고정한다**(서빙 종료). 12:59 시점은 기본 가중에서 9,945다.
- **시간대별 목표의 합은 그날 예약액과 같아야 한다.** 가중치 합이 100%가 아닌 설정이 들어오면 00:00 생성 단계에서 거부하거나 합이 예약액과 일치하도록 보정하고, 어긋난 상태로 저장하지 않는다.
- **진행률을 1.0으로 자르지 않는다.** 계획보다 많이 쓴 캠페인은 1.2를 그대로 반환하고, 1.2 초과 판정은 광고서빙이 한다.
- 소비 기준(1.0 미만 우선, 1.2 초과 시 배분 슬롯 제외, 신규 캠페인 ×0.8)은 광고서빙이 갖는다. 정산은 숫자만 준다(`비즈니스 규칙` 30·34·35행).
- 서빙 시간대 핫패스에서 불리므로 원장 `SUM`이 아니라 카운터와 `budget_hourly_target`만 읽는다.
- **계획 합이 0일 때 진행률은 `0`이다.** V51 `비즈니스 규칙` 34행이 "하루 시작 시점에는 모든 캠페인의 진행률이 0으로 같아 등록이 빠른 캠페인이 먼저 배정되지만, 노출이 시작되면 진행률이 갈라지므로 영향은 하루 첫 회차에 한정된다"고 적어 10:00 정각의 값을 정했다. 모든 캠페인이 같은 `0`이므로 공정성은 동률 처리(캠페인 등록 시각 오름차순)가 맡는다. **그날 예약액이 0인 캠페인은 `PAUSED(NO_POINTS)`라 그날 피드 후보에서 빠지므로**(`검증 시나리오` 27행, 96행 스냅샷) "포인트 없는 캠페인이 1순위로 올라온다"는 상황은 생기지 않는다. 그래도 조회가 들어오면 같은 규칙으로 `0`을 돌려준다.

---

# I-07. 캠페인 상태 변경 수신

저장소 `docs/architecture/런치캐치_도메인_구조와_의존성_설계.md` 2.4의 이벤트 표가 `CampaignStatusChangedEvent`를 **campaign → billing**으로 두고, 정산이 받아서 할 일을 "**과금 판정의 중단 여부 변경과 재개 시 시간대별 목표 소진 재계산**"으로 적는다.

```
수신: campaign.contract.CampaignStatusChangedEvent { campaignId, businessDate, servable, changedAt }
자리: billing 내부 listener/. 발행자와 같은 트랜잭션(@EventListener)
```

1. `changedAt`이 저장된 값보다 **늦을 때만** 반영한다. 2.4의 "수신은 중복과 순서에 상관없이 같은 결과가 나오게 작성한다" 규칙이고, 같은 문단이 "**정산은 중단 표시와 함께 시각을 저장**"이라고 요구한다.
2. 중단으로 바뀌면 그날 차감 경로가 `NOT_BILLED_PAUSED`를 판정한다(103행 "중단 이후 도착"). 서빙이 12:59에 끝나도 노출 인정 시간 30분 동안 노출이 들어오므로 **장중 전이 뒤 최대 30분간 미과금 노출이 발생한다.** 이 노출은 점주에게 과금하지 않는다.
3. 재개로 바뀌면 `budget_hourly_target`을 다시 계산한다(48행). **지난 시간대는 그대로 두고 남은 시간대만** 새 예약액 기준으로 갱신한다.

## 저장 위치 — 스키마 미반영

*(2026-10-12 수정: 이제 반영됐다. 아래 두 컬럼은 `V2__billing_pause_mismatch_date_and_indexes.sql`로 develop에 들어갔다(#129). 절 제목은 경위를 위해 남긴다.)*

```sql
-- budget_daily_plan 에 추가가 필요하다
paused             BOOLEAN      NOT NULL DEFAULT 0   -- 그날 중단 여부
status_changed_at  DATETIME(6)  NULL                 -- 반영한 이벤트의 changedAt
```

- 캠페인×영업일 행이 00:00에 이미 만들어지고(`ACTIVE`·`PAUSED(OWNER)`·`PAUSED(NO_POINTS)` 대상), `NOT_BILLED_PAUSED` 판정이 필요한 범위와 정확히 겹친다.
- 차감 핫패스가 같은 행에서 `unit_price`·`owner_id`를 이미 읽으므로 **조회가 늘지 않는다.**
- `deduction_result`에는 둘 수 없다 — `serve_id` 단위 판정 기록이라 캠페인 단위 상태를 담는 자리가 아니다.
- 이 컬럼은 장중에 갱신된다. `budget_daily_plan` 주석이 "읽기전용 스냅샷"이라 **주석도 함께 고쳐야 한다**(`daily_budget`도 재충전 재예약 때 갱신되므로 이미 읽기전용이 아니다).
- 대안은 별도 `campaign_pause_state`(campaign_id PK) 테이블이다. 영업일과 무관한 현재 상태를 들고 싶을 때 맞지만, 조회가 한 번 늘고 캠페인 상태를 정산이 복제 보관하는 모양이 되어 소유 원칙과 헷갈린다.

DDL은 `billing-api-design.md`의 「추가해야 할 스키마」에 있다(2026-10-09: `스키마 수정 필요 (billing V2).md`는 오래된 문서가 되었다).

---

# 검증 기준

| 항목 | 기준 |
| --- | --- |
| 과금 정합성 | 과금 노출 수 × 적용 단가 = `DEDUCT` 합 − **양수 `ADJUST`(과오 차감 정정) 합** (`비즈니스 규칙` 16행, `검증 시나리오` 22행) |
| 해제 정합성 | 캠페인·영업일별 `RELEASE` = `RESERVE` − `DEDUCT` |
| 상한 | `DEDUCT` 합 ≤ 그날 예약액. 예약액 초과 차감 0건, 잔액 음수 0건 |
| 멱등 | 같은 `serve_id`의 `DEDUCT` 1건, 판정 기록 1건, 카운터 증가 1회 |
| 성능 | 노출 차감 초당 10,000건(비기능 17행) |

상세 시나리오는 `검증시나리오.md`를 따른다.

---

# 아직 정해지지 않은 것

| 항목 | 현재 기준 |
| --- | --- |
| **`budget_daily_plan`의 `paused`·`status_changed_at`** | 스키마에 없다. `NOT_BILLED_PAUSED` 판정과 재개 시 목표 재계산이 둘 다 이 값에 걸려 **차감 구현의 선행 조건**이다. **2026-10-09:** `V2__billing_add_constraints_and_indexes.sql`에 넣는다(`billing-api-design.md` 「추가해야 할 스키마」) **2026-10-12 수정: 해결.** `V2__billing_pause_mismatch_date_and_indexes.sql`로 들어갔다. |
| **`daily_budget` CHECK** | 아직 `> 0`이다. 102행 (5)의 "예약액이 0이어도 넘긴다"를 지키려면 `>= 0`으로 완화해야 한다. **2026-10-09:** 같은 V2 파일에서 처리한다 **2026-10-12 수정: 해결.** 같은 V2에서 `>= 0`으로 완화했다. |
| **`point_ledger`의 `campaign_id` NOT NULL CHECK** | `RESERVE`·`RELEASE`·`DEDUCT`에 대해 DB가 강제하지 않는다. **2026-10-09:** 같은 V2 파일에서 처리한다 **2026-10-12 수정: 해결.** 같은 V2의 `chk_point_ledger_campaign_id`. |
| **서빙 시간대 종료 시각** | `미결정 사항` #23이 12:59와 13:59 사이에서 아직 열려 있다(기본값 12:59). **13:30 해제·대조 시각은 "서빙 종료 + 노출 인정 시간 30분"에서 나온 값이라** 종료가 13:59로 바뀌면 14:30으로 함께 밀린다. 또 `미결정 사항` #22가 노출 인정 시간(30분)도 열어 두었다. **두 시각을 코드에 상수로 박지 말고 설정값으로 읽는다** |
| **단가 설정 키의 조회 메서드** | 운영이 공개할 메서드 이름·시그니처가 정해지지 않았다. 운영은 하위 계층이라 `ops.contract`에 둘 의무는 없고 위치는 운영이 정한다(선례: `ops.scheduler.DailyJob`). **2026-10-09:** 요청은 `campaign·운영 계약 요청.md`로 전달한다 |
| **카운터 수동 재구성 경로** | 98행 예외가 "장애 직후에는 수동으로 실행한다"고 요구하는데 경로가 없다. 운영의 실패 작업 수동 재실행(102행)으로 처리할지, 정산이 관리자 API를 둘지 |
| 재충전 재예약 시 목표 재계산 | 48행(재개)은 재계산이 명시돼 있으나 49행(재충전) 자체의 목표 재계산 여부는 명세에 없다 |
| 00:00 보정 `RELEASE`의 재시도 정책 | 전날 13:30 `RELEASE` 실패분을 00:00이 한 번 더 시도하는데, 이마저 실패할 경우의 재시도 횟수·알림 정책이 없다 **2026-10-12 수정: 정했다(구현 계획 D-10).** 운영 스케줄러의 일시 오류 자동 재실행(최대 2회)에 맡기고, 그래도 실패하면 `FAILED`, 알림, 대사 불일치 기록으로 끝낸다. |
| 102행 (5) 문구 | "예약해 **캠페인에 넘긴다**"가 51행·98행("정산이 가진다, 캠페인은 보관하지 않는다")과 충돌한다. 명세 정정 요청 필요 |
