# 분석 (analytics) API 명세

관리자의 **플랫폼 대시보드·매출 현황·부정 관리**, 사용자의 **사용 통계**, 클라이언트 **이벤트 수집**이다. 프론트와 주고받는 요청·응답만 담는다.

- **집계 로직·원천 테이블·설계 근거·미결 항목**은 [analytics API 설계](../design/analytics/analytics-api-design.md)에 있다. 일별 집계 배치(I-01), 퍼널 이벤트 수신(I-02), 이벤트 로그 보존(I-03)은 [analytics 배치 설계](../design/analytics/analytics-batch-design.md)에 있다.
- 캠페인 리포트는 이 문서 아래 「점주 분석」이 소유한다. 점주 쿠폰 이력은 coupon 도메인이 소유하고 원장과 정산 API는 billing 도메인이 소유한다.
- 점주 일별 리포트 조회 계약은 이 문서의 [점주 일별 리포트](#점주-일별-리포트)가 소유한다.
- 공통 규약(경로, 응답 봉투, 상태 코드, 식별자, 시각, 페이지)은 [공통 API 규약](./api-spec.md)를 따른다.

이 문서는 관리자 분석, 회원 사용 통계, 이벤트 수집, 점주 리포트의 HTTP 계약을 소유한다.

---

## 시계열 조회 규약 (A-01~A-03, A-07)

공통 쿼리는 `period`(`DAILY` / `WEEKLY` / `MONTHLY`), `from`, `to`(KST 날짜, 양끝 포함)다.

| 항목 | 규약 |
| --- | --- |
| 기간 경계 | KST 기준. **주는 월요일 시작.** `label`은 일별 `2026-09-30`, 주별 해당 주 월요일, 월별 `2026-09` |
| 범위 한도 | `DAILY` 최대 92일, `WEEKLY` 최대 53주, `MONTHLY` 최대 13개월. 초과·역전은 `400 ANALYTICS-001` |
| 일부만 걸친 주·월 | `from`~`to` 안에 있는 일자만 합산한다 |
| 정렬 | 시간 오름차순, **포맷팅하지 않은 숫자**, 같은 x축을 쓰는 지표는 한 배열에 담는다 |
| 데이터 없음 | 집계가 끝난 날에 대상 데이터가 없으면 `0`이다. 비어 있는 배열을 내려주지 않는다 |
| 집계 상태 | 버킷마다 `status`: `FINAL`(집계 완료) / `IN_PROGRESS`(당일·집계 전, "집계 중" 표시). 한 버킷 안에 `IN_PROGRESS` 일자가 하나라도 있으면 그 버킷 전체가 `IN_PROGRESS`이고, 그 버킷의 수치 필드는 `0`이 아니라 `null`이다 |
| 집계 시각 | 응답 최상위 `aggregatedAt` = 요청 범위에서 마지막으로 성공한 집계 실행 시각. 성공한 집계가 없으면 `null` |

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "period": "MONTHLY", "from": "2026-07-01", "to": "2026-09-30",
    "aggregatedAt": "2026-10-01T01:05:00+09:00",
    "items": [ { "label": "2026-07", "status": "FINAL" } ]
  }
}
```

### 기간 합산 방식

일별 집계를 주·월로 묶을 때 지표 성격에 따라 다르다. 그래서 같은 응답 안에서도 필드마다 합산 방식이 다르다.

| 성격 | 방식 | 해당 지표 |
| --- | --- | --- |
| 흐름 (그날 발생량) | **합계** | 신규 가입자, 신규 입점, 노출·찜·발급·사용 수, 무효 노출 수, 충전액, 소진액, 예약액, 해제액, 환불액, 조정액 |
| 스냅샷 (그날 말 상태) | **버킷의 마지막 날 값** | 누적 가입자, 누적 입점, 활성 캠페인 수, 탈퇴자, 정지 회원, 미소진 잔액 |
| 비율 | 합산한 분자 / 합산한 분모로 **재계산** | 유효 노출 비율 |
| 활성 사용자 | **일평균** (`avgDailyActiveMembers`) | 주간·월간 순수 활성(WAU/MAU)은 제공하지 않는다 |

---

## API 목록

| # | 메서드 | 경로 | 권한 | 하는 일 |
| --- | --- | --- | --- | --- |
| A-01 | `GET` | `/v1/admin/dashboard/members` | ADMIN, SUPER_ADMIN | 사용자 지표 |
| A-02 | `GET` | `/v1/admin/dashboard/stores` | ADMIN, SUPER_ADMIN | 점주 지표 |
| A-03 | `GET` | `/v1/admin/dashboard/funnel` | ADMIN, SUPER_ADMIN | 노출·찜·발급·사용 퍼널과 유효 노출 비율 |
| A-05 | `POST` | `/v1/admin/aggregations:recalculate` | ADMIN, SUPER_ADMIN | 특정 영업일 집계 재실행 |
| A-06 | `GET` | `/v1/admin/aggregation-runs` | ADMIN, SUPER_ADMIN | 집계 실행 상태 조회 |
| A-07 | `GET` | `/v1/admin/sales` | ADMIN, SUPER_ADMIN | 매출 현황 |
| A-09 | `GET` | `/v1/admin/fraud/invalid-impressions` | ADMIN, SUPER_ADMIN | 무효 노출 사유별 집계 |
| A-10 | `GET` | `/v1/admin/fraud/campaign-warnings` | ADMIN, SUPER_ADMIN | 캠페인 경고 |
| A-12 | `GET` | `/v1/members/me/usage-stats` | MEMBER | 사용 통계 |
| A-13 | `POST` | `/v1/events` | MEMBER | 클라이언트 발생 이벤트 수집 |
| A-14 | `GET` | `/v1/admin/campaign-reports` | ADMIN, SUPER_ADMIN | 캠페인별 소진 포인트와 누적 유효 노출 |

**A-11(`GET /v1/admin/fraud/rate-limit-hits`, 피드 429 집계)은 구현하지 않는다.** 근거는 [설계서](../design/analytics/analytics-api-design.md#1-적용-범위) 1장에 있다.

**A-04(`GET /v1/admin/dashboard/segments`, 카테고리·지역별 분해)와 A-08(`GET /v1/admin/sales/top-owners`, 점주별 상위 소진)도 구현하지 않는다.** 요구사항의 선택 데이터다. 번호는 다시 매기지 않는다.

**화면이 다른 API와 합쳐야 하는 값이 둘 있다.** 분석 응답에 없으니 FE가 함께 호출한다.

| 없는 값 | 어디에 없나 | 대신 호출할 것 |
| --- | --- | --- |
| 원장 불일치 건수 | A-07 | billing의 대사 불일치 조회 응답 `totalElements` |
| 회원 상태 | A-09 | 관리자 회원 목록은 ID 검색이 없어 A-09의 회원 ID와 자동 연결하지 않는다. 회원 상태와 정지 버튼을 이 응답으로 제공하지 않는다 |

---

# 대시보드

관리자가 사용자·플랫폼 지표를 일별/주별/월별로 본다. 해당 요구사항이 요구하는 플랫폼 지표 중 **유효 노출 비율은 A-03**, **충전액·소진액·미소진 잔액은 A-07**, **원장 불일치 건수는 대사 불일치 조회**에서 온다. 점주 개별 성과 지표(찜률·발급률·사용률, 예산 소진율 등)는 점주 리포트 소관이라 여기 없다.

## A-01. `GET /v1/admin/dashboard/members`

```
GET /v1/admin/dashboard/members?period=DAILY&from=2026-09-01&to=2026-09-30
```

### 응답 `200` (항목)

```json
{
  "label": "2026-09-30", "status": "FINAL",
  "newMemberCount": 120, "cumulativeMemberCount": 5430,
  "avgDailyActiveMembers": 910, "withdrawnCount": 48, "suspendedCount": 3
}
```

| 필드 | 뜻 | 합산 |
| --- | --- | --- |
| `newMemberCount` | 그 기간 신규 가입자 | 합계 |
| `cumulativeMemberCount` | 누적 회원 수 | 마지막 날 |
| `avgDailyActiveMembers` | 일평균 활성 회원. **그날 이벤트가 발생한 회원** 기준(로그인 아님) | 일평균 |
| `withdrawnCount` | 탈퇴 회원 수. 집계할 때 `WITHDRAWN` 상태인 회원만 센다. 재가입한 회원은 과거 날짜를 다시 집계할 때 그날 탈퇴 수에서 빠질 수 있다 | 마지막 날 |
| `suspendedCount` | 정지 회원 수. 집계할 때 `SUSPENDED` 상태이고 그날 말까지 정지된 회원만 센다. 정지가 풀리거나 탈퇴한 회원은 과거 날짜를 다시 집계할 때 그날 정지 수에서 빠질 수 있다 | 마지막 날 |

`avgDailyActiveMembers`는 묶음 안 일별 활성 회원의 합을 묶음의 일수로 나눠 소수점 첫째 자리에서 반올림한 정수다. 일부만 걸친 주와 월은 `from`~`to` 안의 일수로 나눈다. `status`가 `IN_PROGRESS`인 항목의 수치 필드는 `0`이 아니라 `null`이다.

사용자 계정 정지 API는 구현 제외다([회원 API 명세](./member.md)). `suspendedCount`는 데이터로 넣은 정지 회원을 센다. 화면은 정지 버튼을 숨긴다.

## A-02. `GET /v1/admin/dashboard/stores`

```
GET /v1/admin/dashboard/stores?period=DAILY&from=&to=
```

```json
{ "label": "2026-09-28", "status": "FINAL", "newStoreCount": 6, "cumulativeStoreCount": 142, "activeCampaignCount": 97 }
```

| 필드 | 뜻 | 합산 |
| --- | --- | --- |
| `newStoreCount` | 신규 입점 | 합계 |
| `cumulativeStoreCount` | 누적 입점 업체 수. 그날 말까지 **최종 등록한 가게** 수이며 점주 상태와 무관하다. 폐업하거나 등록이 취소된 가게도 남는다 | 마지막 날 |
| `activeCampaignCount` | 집행 중 캠페인 수. 그날 말(23:59:59.999999 KST) 기준 `ACTIVE` 상태였던 캠페인 수 | 마지막 날 |

`status`가 `IN_PROGRESS`인 항목의 수치 필드는 `0`이 아니라 `null`이다. 구간 마지막 날의 집계 행이 없으면 `cumulativeStoreCount`와 `activeCampaignCount`도 `null`이다.

## A-03. `GET /v1/admin/dashboard/funnel`

```
GET /v1/admin/dashboard/funnel?period=DAILY&from=&to=
```

```json
{
  "label": "2026-09-30", "status": "FINAL",
  "validImpressionCount": 182000, "invalidImpressionCount": 1450, "validRatio": 0.992,
  "wishCount": 9100, "issueCount": 2300, "redeemCount": 410
}
```

- `redeemCount`는 그날 쿠폰 사용이 확정된 건수다. `redeem` 이벤트가 아니라 쿠폰의 사용 확정 기록으로 센다. 흐름 지표라 기간을 묶을 때 합계다. 전환율 계산은 화면이 한다.
- `validRatio = valid / (valid + invalid)`. 기간을 묶을 때는 합산한 값으로 **재계산**한다.
- `invalidImpressionCount`는 무효 사유 2종(`EXPIRED_OR_UNKNOWN`, `NOT_OWNER`)의 합이다. 사유별 건수는 A-09에서 본다.
- **미과금 노출은 `validImpressionCount`에 포함된다.** 예산을 넘겨 과금되지 않은 노출도 유효 노출이다(해당 요구사항).
- 찜 수는 분석에 수집된 찜 이벤트의 누적 건수다. 이벤트 유실과 보존 기간 차이로 현재 찜 목록이나 원천 업무의 확정 건수와 같다고 보장하지 않는다. 발급·사용 수는 쿠폰의 확정 기록을 따른다.
- 집계가 끝난 날에 대상 데이터가 없으면 수치는 `0`이고 `validRatio`는 `null`이다. `IN_PROGRESS` 버킷의 수치 필드는 공통 시계열 규약대로 모두 `null`이다.

---

## A-05. `POST /v1/admin/aggregations:recalculate`

특정 영업일의 집계를 다시 돌린다. **집계 테이블 값을 직접 받아 쓰지 않는다.**

### 요청

```json
{ "businessDate": "2026-09-30" }
```

- `businessDate`는 **어제 이전만** 허용한다. 오늘·미래는 `422 ANALYTICS-002`.
- 보존 기간(13개월)보다 오래된 날짜도 `422 ANALYTICS-002`다. 원본 노출·이벤트 로그가 지워져 다시 집계하면 값이 0으로 덮이기 때문이다.
- 같은 영업일 집계가 실행 중이면 `409 ANALYTICS-003`.
- 이미 `FINAL`인 날짜도 재실행할 수 있다. 원본이 그대로면 결과도 같고, **실행 도중에도 조회 결과가 비지 않는다.**
- 처리자는 토큰의 관리자 ID다.

### 응답 `202`

```json
{ "code": "SUCCESS", "message": "요청이 성공적으로 처리되었습니다.",
  "data": { "runId": 301, "businessDate": "2026-09-30", "status": "RUNNING" } }
```

실행 결과는 A-06으로 확인한다.

## A-06. `GET /v1/admin/aggregation-runs`

```
GET /v1/admin/aggregation-runs?businessDate=2026-09-30
```

```json
{ "runId": 301, "jobName": "DAILY_AGGREGATION", "businessDate": "2026-09-30",
  "status": "SUCCESS", "startedAt": "2026-10-01T01:00:00+09:00", "finishedAt": "2026-10-01T01:05:00+09:00", "processedCount": 5,
  "executedBy": "batch-02", "retryCount": 0, "failureReason": null }
```

`status`는 `RUNNING` / `SUCCESS` / `FAILED`다. `executedBy`는 실행한 서버이며 작업명은 `DAILY_AGGREGATION`이다. 시작·종료 시각은 KST 오프셋을 포함한다. 실행 중이면 `finishedAt`은 `null`이다.

그 영업일에 실행 기록이 없으면 `data`가 `null`이다. `processedCount`는 성공한 실행이 저장한 집계 행 수이고, 실행 중이거나 실패하면 `null`이다.

---

# 매출 현황

## A-07. `GET /v1/admin/sales`

```
GET /v1/admin/sales?period=MONTHLY&from=2026-07-01&to=2026-09-30
```

### 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "period": "MONTHLY", "from": "2026-07-01", "to": "2026-09-30",
    "aggregatedAt": "2026-10-01T01:05:00+09:00",
    "items": [
      {
        "label": "2026-09", "status": "FINAL",
        "chargeAmount": 5200000, "spentAmount": 4100000,
        "refundAmount": 300000, "adjustAmount": -1000,
        "unspentBalance": 2750000
      }
    ]
  }
}
```

| 필드 | 의미 | 합산 |
| --- | --- | --- |
| `chargeAmount` | 충전액 | 합계 |
| `spentAmount` | 소진액 = 광고 매출. 과거 과금의 정정은 정정 대상 영업일에 붙고 정정이 그날 차감보다 크면 음수일 수 있다. `DEDUCT` 합에서 과오 차감 정정인 양수 `ADJUST` 합을 뺀다. billing의 점주별 합계와 같은 정의다 | 합계 |
| `refundAmount` | 환불액 | 합계 |
| `adjustAmount` | 조정액 (±) | 합계 |
| `unspentBalance` | **미소진 잔액 합계**. 플랫폼이 앞으로 이행해야 할 잔액 | 마지막 날 |

- 당일 데이터는 `IN_PROGRESS`("집계 중")이고 집계 값이 아직 없다.
- **점주별 일·주·월 합계는 이 응답에 없다.** 관리자용 점주별 충전·사용·예약·해제·환불·조정 기간 합계는 billing의 `GET /v1/admin/point-ledger/summary`가 맡는다.
- **`ledgerMismatchCount`는 이 응답에 없다.** 화면이 billing의 대사 불일치 조회를 따로 불러 `totalElements`를 쓴다.
- `reservedAmount`·`releasedAmount`는 내려주지 않는다. 요구사항이 예약·해제를 표시하지 않고 해당 요구사항 필수 데이터에도 없다.
- `unspentBalance`는 그날 말까지의 원장에서 `CHARGE − REFUND + ADJUST − DEDUCT`를 누적해 계산한다. 예약과 해제는 잔액과 예약 사이를 오갈 뿐이라 서로 지워진다.

---

# 부정 관리

**무효 판정 기준과 경고 임계값은 관리자 전용 정보다**. 점주·사용자 화면에 내보내지 않는다.

무효 사유는 둘뿐이다.

| 사유 코드 | 뜻 |
| --- | --- |
| `EXPIRED_OR_UNKNOWN` | 노출 이벤트가 도착했는데 그 `serve_id`의 serve 기록이 없다 |
| `NOT_OWNER` | 다른 사용자가 받은 카드의 `serve_id`를 보냈다 |

`EXPIRED_OR_UNKNOWN`에는 TTL 만료·위조·재전송 세 상황이 섞여 있어 그 자체로 부정 신호가 아니다. 무효 비율 경고와 집중 경고를 함께 보고 판단한다.

## A-09. `GET /v1/admin/fraud/invalid-impressions`

```
GET /v1/admin/fraud/invalid-impressions?from=&to=&reasonCode=&campaignId=&memberId=&page=0&size=20&sortBy=count&sortDir=desc
```

| 파라미터 | 필수 | 뜻 |
| --- | --- | --- |
| `from`, `to` | 예 | 기간(최대 31일). 생략하거나 범위를 넘으면 `400 ANALYTICS-001` |
| `reasonCode` | 아니오 | `EXPIRED_OR_UNKNOWN` / `NOT_OWNER` |
| `campaignId`, `memberId` | 아니오 | 필터 |
| `page`, `size` | 아니오 | 0부터. `size` 기본 20, 최대 100 |
| `sortBy`, `sortDir` | 아니오 | `count`(기본)·`memberId`·`campaignId` / `desc`(기본)·`asc` |

### 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "from": "2026-09-24", "to": "2026-09-30",
    "summaryByReason": [ { "reasonCode": "EXPIRED_OR_UNKNOWN", "count": 1210 },
                         { "reasonCode": "NOT_OWNER", "count": 240 } ],
    "items": [ { "memberId": 55, "campaignId": 12, "reasonCode": "NOT_OWNER", "count": 31 } ],
    "page": 0, "size": 20, "totalElements": 1
  }
}
```

- `summaryByReason`은 필터 조건 전체의 사유별 합계, `items`는 (사용자, 캠페인, 사유) 단위 건수이며 기본 정렬은 건수 내림차순이다. `totalPages`는 내려주지 않는다(`totalElements`와 `size`로 계산).
- **`memberStatus`는 이 응답에 없다.** 관리자 회원 목록의 응답 계약은 [회원 API 명세](./member.md)가 소유한다.
- 관리자 회원 목록은 ID 검색과 회원 상태 변경 기능을 제공하지 않는다. 부정 이용 화면은 `memberId`를 표시하되 회원 목록과 연결하지 않는다.

## A-10. `GET /v1/admin/fraud/campaign-warnings`

```
GET /v1/admin/fraud/campaign-warnings?from=&to=&type=INVALID_RATIO|CONCENTRATION&page=0&size=20&sortBy=invalidRatio&sortDir=desc
```

한 캠페인 단위로 두 경고를 판정해 내려준다. `from`, `to`는 필수이고 기간은 최대 31일이다. `type`을 생략하면 둘 중 하나라도 걸린 캠페인을 모두 내려준다. 페이징은 `page`(0부터)·`size`(기본 20, 최대 100), 정렬은 `sortBy`(`invalidRatio` 기본·`topMembersShare`·`campaignId`)·`sortDir`(`desc` 기본)이며 응답은 `items`와 `page`, `size`, `totalElements`를 담는다.

```json
{
  "campaignId": 12,
  "validCount": 4000, "invalidCount": 1100, "invalidRatio": 0.275, "invalidRatioWarning": true,
  "distinctMemberCount": 4, "topMembersShare": 0.92, "concentrationWarning": true
}
```

| 경고 | 판정 |
| --- | --- |
| `invalidRatioWarning` | `(invalidCount >= 100) AND (validCount == 0 OR invalidRatio > 0.20)`. 무효 노출 비율 20% 초과. `invalidRatio = invalidCount ÷ validCount`이고 `validCount`가 0이면 `null`이다. 유효 노출이 없고 무효가 100건 이상이면 비율 없이 경고한다. 0과 1 사이로 제한되지 않는다 |
| `concentrationWarning` | `(validCount >= 100) AND (topMembersShare > 0.5)`. 소수 계정 집중 |

`topMembersShare`는 상위 5개 계정의 노출 점유율이다. 임계값(상위 계정 수 5, 점유율 0.5, 최소 노출 100, 비율 경고의 최소 무효 건수 100)은 **분석 서버의 설정값**이라 배포로 바뀔 수 있다. 판정식은 [설계서](../design/analytics/analytics-api-design.md#7-부정-관리) 7장에 있다.

집중 경고는 캠페인·영업일 단위로 판정한다. 기간 조회의 `concentrationWarning`은 기간 중 하루라도 판정된 캠페인이면 `true`다. `topMembersShare`와 `distinctMemberCount`는 그 기간에서 점유율이 가장 높았던 하루의 값이다. `validCount`, `invalidCount`, `invalidRatio`는 기간 합계로 계산한다. 기간 전체의 회원별 노출은 보관하지 않는다. 날짜별로 판정하므로 한 달에 걸쳐 조금씩 나눠 소진시키는 공격은 놓칠 수 있다. 무효 비율 경고와 함께 본다.

---

# 관리자 캠페인 리포트

## A-14. `GET /v1/admin/campaign-reports`

```
GET /v1/admin/campaign-reports?campaignIds=12,15,31&from=2026-09-01&to=2026-09-30
```

캠페인과 포스터 목록에 표시할 소진 포인트와 누적 유효 노출을 한 번에 조회한다. 클라이언트는 캠페인 목록 응답과 이 응답을 캠페인 ID로 합친다.

| 파라미터 | 필수 | 뜻 |
| --- | --- | --- |
| `campaignIds` | 예 | 쉼표로 나눈 양수 ID. 최대 100개. 같은 ID 를 두 번 보내면 처음 자리에 한 번만 내려준다 |
| `from`, `to` | 아니오 | 둘 다 주거나 둘 다 생략한다. 생략하면 각 캠페인의 집계된 전체 기간. 지정하면 양끝 포함 최대 92일 |

```json
{ "code": "SUCCESS", "message": "요청이 성공적으로 처리되었습니다.",
  "data": { "items": [ { "campaignId": 12, "spentAmount": 84000, "validImpressionCount": 9100, "todayStatus": "IN_PROGRESS" } ] } }
```

| 필드 | 뜻 |
| --- | --- |
| `spentAmount` | 기간 소진 포인트 = 과금액 − 정정액. 점주 리포트와 같은 정의 |
| `validImpressionCount` | 기간 누적 유효 노출 (미과금 포함) |
| `todayStatus` | 당일은 집계 전이라 항상 `IN_PROGRESS`. 값은 전일 확정 기준이다(해당 요구사항) |

집계가 없는 캠페인은 두 값이 `0`이다. 응답 순서는 요청한 `campaignIds` 순서다. 소진 포인트는 정정이 차감보다 큰 날이 있으면 음수일 수 있다.

### 오류

| 상태 | 코드 | 조건 |
|---|---|---|
| `400` | `COMMON-002` | `campaignIds` 가 없거나 비었다. 100개를 넘는다. 양수가 아니거나 숫자가 아닌 ID 가 있다 |
| `400` | `ANALYTICS-001` | `from`, `to` 중 하나만 보냈다. `from` 이 `to` 보다 늦다. 92일을 넘는다 |
| `401` | 공통 인증 코드 | 인증 쿠키가 없거나 유효하지 않다 |
| `403` | 공통 인가 코드 | `ADMIN`, `SUPER_ADMIN` 이 아니다 |

---

# 사용자 통계와 이벤트 수집

## A-12. `GET /v1/members/me/usage-stats`

마이페이지 상단 3칸 요약이다. 본인 데이터만 조회한다.

```json
{ "code": "SUCCESS", "message": "요청이 성공적으로 처리되었습니다.",
  "data": { "wishCount": 12, "redeemCount": 5, "savedAmount": 18000 } }
```

| 필드 | 뜻 |
| --- | --- |
| `wishCount` | 이 회원의 `wish` 이벤트 **누적** 건수. 찜 해제 이벤트가 없어 현재 찜 목록 개수와 다를 수 있다. 세 값 모두 가입 이후 전체 누적이다(13개월 보존 삭제와 무관) |
| `redeemCount` | 이 회원의 확정된 쿠폰 사용 누적 건수 |
| `savedAmount` | 사용 완료된 쿠폰의 확정 할인 금액 합. 정액 할인은 할인액, 정률 할인은 실제 결제 금액이 확정된 경우만 더한다 |

이벤트가 없으면 세 값 모두 `0`이다.

세 값은 전날까지 확정된 일별 값을 합산하므로 오늘 사용한 쿠폰은 다음 날 반영된다. 사용 수와 절약 금액은 `redeem` 이벤트가 아니라 쿠폰의 사용 확정 기록으로 센다.

## A-13. `POST /v1/events`

**클라이언트가 직접 보내는 유일한 수집 API다.** 서버가 알 수 없는 **화면 행동 3종만** 받는다. 노출·찜·발급·사용 등 서버 업무 이벤트는 이 API로 보내지 않는다.

| 허용 유형 | 설명 | `campaignId`·`storeId` |
| --- | --- | --- |
| `poster_open` | 포스터 상세 진입 | **필수** |
| `map_view` | 지도 진입 | **보내지 않는다** |
| `wishlist_view` | 찜 목록 진입 | **보내지 않는다** |

그 외 유형을 보내면 `400 ANALYTICS-004`다. 클라이언트가 노출·발급 같은 과금·성과 이벤트를 위조하지 못하게 한다.

### 요청

```json
{
  "events": [
    { "eventId": "0f8b0d6e-...", "eventType": "poster_open",
      "occurredAt": "2026-10-01T11:02:10+09:00", "campaignId": 12, "storeId": 3 }
  ]
}
```

| 필드 | 필수 | 뜻 |
| --- | --- | --- |
| `eventId` | 예 | 클라이언트가 만든 고유 ID. **중복 방지 키** |
| `eventType` | 예 | 위 세 가지 |
| `occurredAt` | 예 | 클라이언트 기준 발생 시각 |
| `campaignId`, `storeId` | `poster_open`만 | `map_view`·`wishlist_view`는 보내면 `400 ANALYTICS-005` |
| `wishId` | 아니오 | `wishlist_view`에만 허용 |

- `memberId`는 **본문이 아니라 토큰에서** 가져온다.
- 한 번에 최대 50건이다. 초과는 `400 ANALYTICS-005`.
- **`occurredAt`이 수신 시각보다 미래이면 수신 시각으로 보정한다**(클라이언트 시계 오차).
- 같은 `eventId`를 두 번 받으면 **1건만 저장**하고 두 번째는 무시한다(오류가 아니다).
- 응답은 `202`다. 화면을 벗어나면서 보낸 요청은 응답을 못 받을 수 있으므로 **클라이언트는 응답에 의존하지 않는다.**

```json
{ "code": "SUCCESS", "message": "요청이 성공적으로 처리되었습니다.",
  "data": { "accepted": 1, "duplicated": 0 } }
```

세 유형 모두 지금 보낼 수 있다.

---

# 점주 일별 리포트

로그인한 점주가 본인 가게의 날짜별 캠페인 성과와 포인트 소진 내역을 조회한다. 권한은 `OWNER`다. 서버는 로그인 정보에서 점주 ID를 확인하므로 요청으로 받지 않는다.

```http
GET /v1/owner/report?from=2026-10-16&to=2026-10-17
```

| 파라미터 | 위치 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| `from` | Query | string(date) | O | 조회 시작일을 `YYYY-MM-DD`로 보낸다 |
| `to` | Query | string(date) | O | 조회 종료일을 `YYYY-MM-DD`로 보낸다 |

조회 범위는 시작일과 종료일을 포함해 최대 30일이다. `from`은 `to`보다 늦을 수 없다. 하루만 조회할 때는 두 날짜를 같게 보낸다.

성공하면 `200`과 공통 성공 봉투를 반환한다. `data`에는 다음 필드가 있다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `from` | string(date) | 조회 시작일이다 |
| `to` | string(date) | 조회 종료일이다 |
| `totalSpentAmount` | integer(int64) | `items[].spentAmount`를 합한 값이다 |
| `items` | array(object) | 날짜 오름차순의 캠페인 집계다. 같은 날에는 캠페인 ID 오름차순으로 반환한다 |

데이터는 일별 배치가 반영한 집계값이다. 해당 점주의 집계가 없으면 `items`는 빈 배열이고 `totalSpentAmount`는 0이다. 아직 집계되지 않은 날은 항목을 만들지 않는다.

`data.items[]`의 필드는 다음과 같다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `businessDate` | string(date) | 집계 영업일이다 |
| `campaignId` | integer(int64) | 해당 날짜에 집행한 캠페인 ID다 |
| `dailyBudget` | integer(int64) | 해당 영업일에 실제 예약·배정된 포인트다. 점주가 설정한 예산과 다를 수 있다 |
| `impressionUnitCost` | integer(int64) | 해당 영업일에 적용된 과금 노출 1회당 단가다 |
| `validImpressionCount` | integer(int64) | 유효 노출 수다. 미과금 유효 노출을 포함한다 |
| `invalidImpressionCount` | integer(int64) | 무효 노출 수다 |
| `wishCount` | integer(int64) | 해당 영업일에 발생한 찜 건수다. 현재 찜한 사용자 수와 다르다 |
| `issuedCount` | integer(int64) | 해당 영업일 쿠폰 회차의 발급 건수다 |
| `redeemCount` | integer(int64) | 해당 영업일에 사용된 쿠폰 건수다. 다른 날짜에 발급된 쿠폰도 포함한다 |
| `chargedAmount` | integer(int64) | 과금으로 차감된 포인트다 |
| `refundedAmount` | integer(int64) | 과오 차감 정정으로 돌려준 포인트다. 일반 잔액 환불액은 포함하지 않는다 |
| `spentAmount` | integer(int64) | `chargedAmount - refundedAmount`다. 정정액이 과금액보다 크면 음수다 |

| 상태 | 오류 코드 | 조건 |
|---|---|---|
| `400` | `COMMON-003` | 필수 날짜가 없거나 날짜 형식이 잘못되었다 |
| `400` | `ANALYTICS-001` | `from`이 `to`보다 늦거나 조회 범위가 30일을 초과했다 |
| `401` | `AUTH-005` | 로그인하지 않았거나 인증이 유효하지 않다 |
| `403` | `AUTH-006` | `OWNER` 역할이 없다 |

---

# 점주 분석

점주가 자신의 캠페인 광고 성과를 조회한다. 리포트 집계와 데이터 소유, 성능 기준은 [analytics API 설계](../design/analytics/analytics-api-design.md)에 있다. 쿠폰 발급·사용 이력은 coupon 도메인의 `GET /v1/owner/stores/{storeId}/coupon-usages`가 소유한다.

아래 소유 확인 표는 상세 리포트와 변경 이력에 적용한다. 이벤트 로그 기반 성과 조회는 캠페인 공개 contract로 현재 소유를 확인하며 해당 절의 오류 규칙을 따른다. 점주 일별 리포트는 로그인한 점주의 집계만 조회한다.

| 상황 | 응답 |
| --- | --- |
| 요청 기간에 집계 행이 있고 소유 점주가 요청한 점주가 아니다 | `403` |
| 요청 기간에 집계 행이 없다 | 본인 캠페인인지와 관계없이 빈 `items`. 남의 캠페인이어도 같아서 존재 여부가 새지 않는다 |
| 당일 | `AGGREGATING` |

이전에는 남의 캠페인이나 없는 캠페인이면 존재 여부와 관계없이 `403`이었다.

| API | 권한 | 설명 |
| --- | --- | --- |
| `GET /v1/owner/campaigns/{campaignId}/reports` | ACTIVE OWNER | 광고주 노출 리포트 |
| `GET /v1/owner/campaigns/{campaignId}/reports/{businessDate}/history` | ACTIVE OWNER | 확정 뒤 변경 내역 |
| `GET /v1/owner/campaigns/{campaignId}/analytics/funnel` | OWNER | 요청 시 이벤트 로그로 캠페인 성과 계산 |

## 이벤트 로그 기반 캠페인 성과

`GET /v1/owner/campaigns/{campaignId}/analytics/funnel?from=2026-10-01&to=2026-10-07`

점주 본인의 캠페인만 조회한다. 인증은 공통 규약의 액세스 토큰 쿠키를 사용한다. 요청 본문은 없다.

| 파라미터 | 위치 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| `campaignId` | 경로 | integer | 예 | 양수인 캠페인 ID |
| `from` | 쿼리 | string(date) | 예 | 한국 시각의 조회 시작일. 해당 날짜를 포함한다 |
| `to` | 쿼리 | string(date) | 예 | 한국 시각의 조회 종료일. 해당 날짜를 포함한다 |

`from <= to`여야 하며 양끝 포함 최대 31일이다. 오늘을 포함할 수 있다. 요청 시점에 저장된 이벤트를 조회하며 배치 완료를 기다리지 않는다. `status`와 `aggregatedAt`은 반환하지 않는다.

### 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "campaignId": 801,
    "from": "2026-10-01",
    "to": "2026-10-07",
    "impressionCount": 1000,
    "wishCount": 200,
    "passCount": 600,
    "issuedCount": 100,
    "redeemCount": 60,
    "wishRate": 0.2,
    "passRate": 0.6,
    "wishToIssueRate": 0.5,
    "issueToRedeemRate": 0.6
  }
}
```

| 필드 | 타입 | null 가능 | 의미 |
|---|---|---|---|
| `campaignId` | integer | 아니오 | 조회한 캠페인 ID |
| `from`, `to` | string(date) | 아니오 | 조회한 날짜 범위 |
| `impressionCount` | integer(int64) | 아니오 | 기간 중 기록된 유효 노출(`impression`) 건수 |
| `wishCount` | integer(int64) | 아니오 | 기간 중 기록된 피드의 찜 스와이프(`wish`) 건수 |
| `passCount` | integer(int64) | 아니오 | 기간 중 기록된 패스 스와이프(`pass`) 건수 |
| `issuedCount` | integer(int64) | 아니오 | 기간 중 기록된 쿠폰 발급(`issue`) 건수 |
| `redeemCount` | integer(int64) | 아니오 | 기간 중 기록된 쿠폰 사용(`redeem`) 건수 |
| `wishRate` | number | 예 | `wishCount / impressionCount` |
| `passRate` | number | 예 | `passCount / impressionCount` |
| `wishToIssueRate` | number | 예 | `issuedCount / wishCount` |
| `issueToRedeemRate` | number | 예 | `redeemCount / issuedCount` |

- 건수는 회원 수나 현재 남아 있는 찜 수가 아니라 기간 중 발생한 이벤트 수다. 가게 상세에서 누른 찜은 수집하지 않으므로 포함하지 않는다.
- 비율은 소수점 여섯 자리로 반올림한다. `0.2`는 20%이며 분모가 0이면 `null`이다. 기록이 없으면 모든 건수는 0이고 모든 비율은 `null`이다.
- 같은 기간의 발생 건수끼리 나눈다. 같은 고객이나 같은 쿠폰의 전환을 추적하는 계약이 아니므로 비율이 1을 넘을 수 있으며 상한을 적용하지 않는다.
- 이벤트의 비동기 기록은 지연되거나 누락될 수 있다. 이 통계는 원천 업무의 확정 건수나 과금 근거를 대신하지 않는다.

### 오류

| 상태 | 코드 | 조건 |
|---|---|---|
| `400` | `COMMON-002` | 양수가 아닌 캠페인 ID |
| `400` | `COMMON-003` | 필수값 누락, 날짜 형식 오류, 식별자 타입 오류 |
| `400` | `ANALYTICS-001` | 기간이 역전되거나 31일을 초과함 |
| `401` | 공통 인증 코드 | 인증 쿠키가 없거나 유효하지 않음 |
| `403` | `AUTH-006` | 점주 역할이 아니거나 본인 소유가 아닌 캠페인. 없는 캠페인도 동일하게 처리함 |

## 광고주 노출 리포트

`GET /v1/owner/campaigns/{campaignId}/reports?period=DAILY&from=2026-10-01&to=2026-10-31`

`period`는 `DAILY`, `WEEKLY`, `MONTHLY` 중 하나이고 `from`, `to`는 필수다. 최대 조회 기간은 31일이다. 주·월 값은 일별 값을 아래 규칙으로 묶는다.

- 건수·금액은 합산한다.
- `budgetSpendRate`는 합산한 `spentAmount`를 기간에 포함된 일별 예산의 합으로 나눠 다시 계산한다. 예산 합이 0이면 `null`이다.
- `impressionUnitCost`는 `WEEKLY`와 `MONTHLY` 응답에서 `null`이다.
- `costPerVisit`는 합산 `spentAmount / redeemCount`이며 `redeemCount=0`이면 `null`이다.
- `finalizedAt`은 기간 안의 마지막 확정 시각이고 `changedAfterFinalization`은 하루라도 변경 이력이 있으면 `true`다.

```json
{
  "code": "SUCCESS",
  "data": {
    "campaignId": 801,
    "period": "DAILY",
    "from": "2026-10-01",
    "to": "2026-10-31",
    "summary": {
      "spentAmount": 42000,
      "validImpressionCount": 5100,
      "notBilledImpressionCount": 300,
      "invalidImpressionCount": 20,
      "wishCount": 430,
      "issuedCount": 210,
      "redeemCount": 84,
      "costPerVisit": 500
    },
    "items": [
      {
        "businessDate": "2026-10-01",
        "dailyBudget": 10000,
        "chargedAmount": 8400,
        "refundedAmount": 0,
        "spentAmount": 8400,
        "budgetSpendRate": 0.84,
        "impressionUnitCost": 2,
        "validImpressionCount": 4500,
        "billableImpressionCount": 4200,
        "notBilledImpressionCount": 300,
        "invalidImpressionCount": 20,
        "notBilledReasons": { "NOT_BILLED_BUDGET": 180, "NOT_BILLED_PAUSED": 80, "NOT_BILLED_SOLD_OUT": 40 },
        "invalidReasons": { "EXPIRED_OR_UNKNOWN": 12, "NOT_OWNER": 8 },
        "hourlyImpressions": [{ "hour": 10, "impressionCount": 1500 }],
        "slotImpressions": { "ALLOCATION": 1350, "RELEVANCE": 3150 },
        "wishCount": 380,
        "issuedCount": 190,
        "redeemCount": 76,
        "costPerVisit": 111,
        "reportStatus": "FINALIZED",
        "finalizedAt": "2026-10-02T00:20:00+09:00",
        "changedAfterFinalization": true
      }
    ]
  }
}
```

| 응답 필드 | 뜻 |
| --- | --- |
| `refundedAmount` | 과오 차감 정정액. 그 캠페인에 대해 기록된 정정만 센다. 캠페인 없이 기록된 정정은 플랫폼 합계(A-07)에만 들어간다 |
| `notBilledReasons` | 미과금 사유별 노출 수 |
| `invalidReasons` | 무효 사유별 노출 수 |
| `hourlyImpressions` | 0~23시 중 유효 노출이 있는 시간의 시간대별 유효 노출 수 |
| `slotImpressions` | 슬롯 이름표별 유효 노출 수. 키는 피드 엔진이 붙인 이름표이고 고정된 목록이 아니다. 0건인 이름표는 키가 없다 |

나머지 필드는 아래 「일별 응답 필드」에 있다. 각 값을 어디서 읽는지는 [설계서](../design/analytics/analytics-api-design.md#9-캠페인-리포트) 9장에 있다.

무효 노출은 유효 노출에 포함하지 않고 과금하지 않는다. 미과금 노출은 유효 노출에 포함하지만 `spentAmount`에는 포함하지 않는다. 과금 노출 수에 단가를 곱해 차감액을 검증한다.

## 확정 뒤 변경 내역

`GET /v1/owner/campaigns/{campaignId}/reports/{businessDate}/history`

일별 리포트가 `FINALIZED`가 된 뒤 지연 도착 이벤트 또는 정정으로 바뀌면 변경 전 값, 변경 후 값, 변경 시각, 변경 사유를 반환한다. 기본 리포트의 `changedAfterFinalization`은 이 이력이 존재할 때만 `true`다. 점주는 이 API로 요구사항이 요구하는 변경 내역을 확인한다.

당일 리포트는 `AGGREGATING` 상태로 반환하며 확정 전 수치라는 점을 화면에 표시한다. 찜·발급·사용은 성과 지표이며 정산 금액에 포함하지 않는다.

---

## 광고주 리포트 상세 계약

### 요청 검증

`from`, `to`는 KST 영업일이고 `from <= to`여야 한다. 일별·주별·월별 모두 같은 원천 일별 집계를 묶어 계산한다. 아직 확정되지 않은 당일을 포함하면 해당 구간은 `AGGREGATING` 상태로 반환한다.

| `period` | 항목의 `businessDate` 표현 | 합산 범위 |
| --- | --- | --- |
| `DAILY` | `yyyy-MM-dd` | 하루 |
| `WEEKLY` | 그 주 월요일 날짜 | 월요일~일요일 |
| `MONTHLY` | `yyyy-MM` | 달력 월 |

### 일별 응답 필드

| 필드 | 설명 |
| --- | --- |
| `businessDate` | 영업일 |
| `dailyBudget` | 해당 영업일 예약된 하루 예산 |
| `chargedAmount` | 과금된 유효 노출의 차감 합 |
| `refundedAmount` | 사후 과오 차감 정정액 |
| `spentAmount` | `chargedAmount - refundedAmount` |
| `budgetSpendRate` | `spentAmount / dailyBudget`. 분모 0이면 `null` |
| `impressionUnitCost` | 그날 적용된 노출 단가 |
| `validImpressionCount` | 과금·미과금 유효 노출 합 |
| `billableImpressionCount` | 실제 과금된 유효 노출 수 |
| `notBilledImpressionCount` | 과금되지 않은 유효 노출 수. 세 미과금 사유의 합 |
| `invalidImpressionCount` | 무효 노출 수 |
| `wishCount`, `issuedCount`, `redeemCount` | 찜·발급·사용 성과 |
| `costPerVisit` | `spentAmount / redeemCount`. `redeemCount=0`이면 `null` |

`chargedAmount`는 광고 비용이고 환불 요청으로 발생한 `REFUND`와 혼동하지 않는다. `refundedAmount`는 고객 환불이 아니라 과오 차감 사실이 확인됐을 때의 관리자 정정이다. 정산 `GET /v1/owner/points/spend-summary`의 `spentPoint`는 정정을 빼지 않은 집행액이라 정정이 있으면 이 리포트의 `spentAmount`와 정정액만큼 다르다.

### 노출 지표 규칙

```text
validImpressionCount = billableImpressionCount + notBilledImpressionCount
spentAmount = chargedAmount - refundedAmount
costPerVisit = spentAmount / redeemCount
```

| 구분 | 코드 | 리포트 처리 |
| --- | --- | --- |
| 무효 | `EXPIRED_OR_UNKNOWN` | 유효 노출·과금에서 제외, 사유별 표시 |
| 무효 | `NOT_OWNER` | 유효 노출·과금에서 제외, 사유별 표시 |
| 미과금 | `NOT_BILLED_BUDGET` | 유효 노출에는 포함, 과금에는 제외 |
| 미과금 | `NOT_BILLED_PAUSED` | 유효 노출에는 포함, 과금에는 제외 |
| 미과금 | `NOT_BILLED_NO_PLAN` | 유효 노출에는 포함, 과금에는 제외. 노출을 받은 날에 그 캠페인의 예산 계획이 없었다(00:00 예약 전이거나 전날 집행이 끝난 캠페인). |
| 미과금 | `NOT_BILLED_CLOSED` | 유효 노출에는 포함, 과금에는 제외. 노출을 받은 영업일의 다음 날 00:00 뒤에 정산이 처리했다. 그날 집계가 끝난 뒤에 늘 수 있어 다음 날의 재집계에서 리포트 값이 바뀔 수 있다 |
| 미과금 | `NOT_BILLED_SOLD_OUT` | 유효 노출에는 포함, 과금에는 제외 |

미과금 노출은 광고 성과에는 포함되지만 점주에게 비용으로 청구되지 않는다.

### 시간대·슬롯·전환 지표

`hourlyImpressions`는 0~23시 중 유효 노출이 저장된 시간의 노출 수를 시간순으로 반환한다. 노출이 없는 시간은 원소가 없고 화면이 0으로 채운다. `slotImpressions`는 `ALLOCATION`, `RELEVANCE`의 유효 노출 수를 반환한다. 슬롯 유형 비율은 각 값 ÷ `validImpressionCount`로 화면에서 계산한다. 분모가 0이면 비율은 `null`이다.

찜·발급·사용 수와 전환율은 광고 성과 지표다. 정산 원장과는 독립이며 점주 리포트 화면에 “전환 지표는 비용 정산에 반영되지 않음”을 표시한다.

| 전환율 | 계산 |
| --- | --- |
| 찜률 | `wishCount / validImpressionCount` |
| 발급률 | `issuedCount / validImpressionCount` |
| 사용률 | `redeemCount / issuedCount` |
| 찜→발급 전환율 | `issuedCount / wishCount` |

분모가 0인 전환율은 `null`이다. 기간을 묶을 때는 일별 비율 평균이 아니라 합산한 분자와 분모로 다시 계산한다.

---

## 변경 이력 상세 계약

### 요청과 응답

```http
GET /v1/owner/campaigns/801/reports/2026-10-01/history
```

```json
{
  "code": "SUCCESS",
  "data": {
    "campaignId": 801,
    "businessDate": "2026-10-01",
    "items": [
      {
        "changedAt": "2026-10-03T01:10:00+09:00",
        "reason": "LATE_EVENT",
        "before": { "validImpressionCount": 4500, "spentAmount": 8400 },
        "after": { "validImpressionCount": 4503, "spentAmount": 8406 }
      }
    ]
  }
}
```

| 필드 | 설명 |
| --- | --- |
| `changedAt` | 재집계·정정이 반영된 시각 |
| `reason` | 변경 사유 텍스트. 정해진 값 목록이 없는 자유 문자열이며, 예: `LATE_EVENT`, `OVERCHARGE_REVERSAL`, `MANUAL_RECALCULATION` |
| `before` | 변경 전 공개 지표 |
| `after` | 변경 후 공개 지표 |

`FINALIZED` 이전의 집계 중 변경은 이력으로 만들지 않는다. 이력이 없으면 `items: []`를 반환한다.

---

# 오류 코드

`ANALYTICS-` 코드를 이 문서가 소유한다.

| 코드 | 상태 | 언제 |
| --- | --- | --- |
| `ANALYTICS-001` | `400` | 기간 형식 오류, `from > to`, 범위 한도 초과 |
| `ANALYTICS-002` | `422` | 집계할 수 없는 날짜(오늘, 미래, 보존 기간 13개월보다 오래된 날) |
| `ANALYTICS-003` | `409` | 그 영업일의 집계가 이미 실행 중 |
| `ANALYTICS-004` | `400` | 허용되지 않은 이벤트 유형(클라이언트가 보낼 수 없는 유형) |
| `ANALYTICS-005` | `400` | 이벤트 일괄 전송 한도(50건) 초과, 필수 값 누락, 또는 `map_view`·`wishlist_view`에 `campaignId`·`storeId`를 보냄 |

권한이 없으면 `403`이고 인증되지 않았거나 만료·폐기된 Access Token이면 `401`이다. 자세한 계약은 [공통 API 규약](./api-spec.md)을 따른다.
