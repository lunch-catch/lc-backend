# 결제·포인트·정산 (billing) API 명세

**설계서:** 서버 안의 처리 순서와 정한 이유는 [billing API 설계](../design/billing/billing-api-design.md)가, 배치와 내부 흐름은 [정산 배치 설계](../design/billing/billing-batch-design.md)가 갖는다.

점주의 **충전·잔액 조회·환불 요청**과 관리자의 **결제 내역·포인트 변동·환불 처리·정책·조정·대사 조회**를 다룬다.


00:00 예약·해제 배치, 노출 차감, 대사 배치처럼 HTTP가 아닌 내부 흐름은 [정산 배치 설계](../design/billing/billing-batch-design.md)에서 다룬다. 관리자 대시보드와 매출 현황(FR-017, FR-022)은 `analytics-api-spec.md`가, 점주 리포트는 `analytics-api-spec.md`(점주 분석)가 소유한다.

공통 규약(경로, 응답 봉투, 상태 코드, 식별자, 시각, 페이지)은 [api-spec.md](./api-spec.md)를 따르고 여기서는 반복하지 않는다.

점주 API는 점주 상태가 `ACTIVE`일 때만 쓸 수 있다. `ONBOARDING`과 `SUSPENDED`는 `403`이다.

---

## API 목록

### 점주 (`OWNER`)

| 메서드 | 경로 | 하는 일 | 기능 ID |
| --- | --- | --- | --- |
| `POST` | `/v1/owner/payments` | 결제 주문 생성 | FR-046 |
| `POST` | `/v1/owner/payments/{paymentId}:confirm` | PG 승인 확정과 충전 | FR-046 |
| `GET` | `/v1/owner/payments/{paymentId}` | 결제 상태 조회 | FR-046 |
| `GET` | `/v1/owner/payments` | 내 결제 내역 | FR-046 |
| `POST` | `/webhook/payments` | PG 결제 상태 웹훅 (공개, PG 호출, 보조 경로) | FR-046 |
| `GET` | `/v1/owner/point-policy` | 충전 화면용 정책 조회 | FR-046 |
| `GET` | `/v1/owner/points/balance` | 잔액과 예약 중 포인트 | FR-047 |
| `GET` | `/v1/owner/points/ledger` | 포인트 변동 내역 | FR-047 |
| `GET` | `/v1/owner/points/spend-summary` | 캠페인·일별 소진 집계 | FR-047 |
| `GET` | `/v1/owner/refund-requests/preview` | 환불 정책과 예상 환불액 | FR-049 |
| `POST` | `/v1/owner/refund-requests` | 환불 요청 | FR-049 |
| `GET` | `/v1/owner/refund-requests` | 내 환불 요청 목록 | FR-049 |

### 관리자 (`ADMIN`, `SUPER_ADMIN`)

| 메서드 | 경로 | 하는 일 | 기능 ID |
| --- | --- | --- | --- |
| `GET` | `/v1/admin/payments` | 결제(충전) 내역 조회 | FR-019 |
| `POST` | `/v1/admin/payments/{paymentId}:recheck` | `UNKNOWN` 결제 재조회 | FR-019 |
| `GET` | `/v1/admin/point-ledger` | 점주별 포인트 변동 조회 | FR-021 |
| `GET` | `/v1/admin/point-ledger/summary` | 점주별 일·주·월 합계 | FR-021 |
| `GET` | `/v1/admin/refund-requests` | 환불 요청 목록 | FR-020 |
| `POST` | `/v1/admin/refund-requests/{refundRequestId}:approve` | 환불 승인 | FR-020 |
| `POST` | `/v1/admin/refund-requests/{refundRequestId}:reject` | 환불 반려 | FR-020 |
| `GET` | `/v1/admin/point-policy`, `/history` | 정책과 변경 이력 | FR-018 |
| `PUT` | `/v1/admin/point-policy/next` | 내일 00:00 적용 정책 저장 | FR-018 |
| `POST` | `/v1/admin/point-adjustments` | 포인트 수동 조정 | FR-101 |
| `GET` | `/v1/admin/settlement-mismatches` | 정산 대사 불일치 조회 | FR-019, FR-021 |

관리자 API의 처리자는 요청 본문이 아니라 **토큰의 주체**에서 가져오고, 모두 운영 도메인의 감사 로그에 남는다.

`POST /v1/admin/point-adjustments`와 `GET /v1/admin/settlement-mismatches`는 명세에 전용 행이 없다. 전자는 FR-101(감사 로그)이 "포인트 조정"을 기록 대상으로 적고 `ADJUST`가 과오 차감 정정의 유일한 경로여서 두고, 후자는 FR-019, FR-021이 "불일치를 검출해 기록하고 매출 현황에 반영"을 요구해 **개별 건을 보는 화면**으로 둔다.

---

## 추가 공통 규약

| 항목 | 규약 |
| --- | --- |
| 금액 | 정수. **1원 = 1포인트.** 음수는 원장 변동에서만 쓴다 |
| 잔액 | **저장하지 않는다.** 항상 원장에서 계산한다. `DEDUCT`가 아닌 행의 `SUM(amount)`이다. **`DEDUCT`는 제외한다** (소진분은 `RESERVE`에서 이미 빠져 있어 또 빼면 이중 차감, 요구사항 명세서 `비즈니스 규칙` 시트 16행). 캐시로 대신 내려주지 않는다(FR-047 예외). V51의 `CHARGE − RESERVE + RELEASE − REFUND + ADJUST`는 크기 기준 표기다. 원장은 부호를 포함해 저장하므로(`RESERVE`·`REFUND`는 음수) 그 식을 그대로 구현하면 부호가 뒤집힌다 |
| 영업일 | KST 기준, 00:00에 바뀐다. `business_date`는 서버가 `Asia/Seoul` 기준으로 계산해 넣고 **DB 날짜 함수를 쓰지 않는다**(요구사항 명세서 비기능 시트 42행) |
| 오류 코드 | `BILLING-xxx`. 이 문서가 소유한다 |

### 원장 유형 (`point_ledger.entry_type`)

| 유형 | 부호 | 언제 |
| --- | --- | --- |
| `CHARGE` | + | 결제가 `APPROVED`로 확정될 때만. 결제당 1건 |
| `RESERVE` | − | 00:00에 그날 쓸 포인트를 캠페인 몫으로 배정, 또는 충전 직후 재예약 |
| `DEDUCT` | − | 과금(`BILLED`) 노출 1건당 1행. 그날 예약액 안에서만 발생 |
| `RELEASE` | + | 다음 날 00:00에 전날 미소진 예약분을 잔액으로 반환 |
| `REFUND` | − | 점주 환불 승인 시 |
| `ADJUST` | ± | 관리자 수동 조정. 사유 필수, `source=MANUAL` 고정 |

**`REFUND_INVALID`는 존재하지 않는다.** 과오 차감의 사후 복구는 근거와 멱등키를 가진 양수 `ADJUST`로 기록한다. V51 전 시트에 `REFUND_INVALID` 표현은 남아 있지 않다.

---

# 결제와 충전

## `POST /v1/owner/payments`

PG 결제창을 열기 **전에** 주문을 먼저 만든다. PG는 가맹점이 발급한 `orderId`를 승인 요청의 금액과 대조해 위변조를 막기 때문에, 이 사전 등록 없이는 승인 확정에서 금액을 검증할 수 없다.

### 요청

```json
{ "amount": 30000 }
```

| 필드 | 필수 | 뜻 | 저장 |
| --- | --- | --- | --- |
| `amount` | 예 | 결제 금액(원) | `payment.amount` |


### 응답 `201`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": { "paymentId": 101, "orderId": "ord_20261001_abc123", "amount": 30000 }
}
```

프론트는 이 `orderId`·`amount`로 PG 결제창을 연다.

### 오류

| 응답 | 코드 | 언제 |
| --- | --- | --- |
| `400` | `BILLING-001` | 최소 충전 금액 미만. 주문을 만들지 않는다 |
| `400` | `BILLING-002` | 충전 상품에 없는 금액. 주문을 만들지 않는다 |
| `409` | `BILLING-008` | 확인 중(`UNKNOWN`)인 결제가 있어 새 주문을 만들 수 없다 |

---

## `POST /v1/owner/payments/{paymentId}:confirm`

점주가 PG 결제창에서 승인을 받은 뒤 프론트가 호출한다.

### 요청

```json
{ "orderId": "ord_20261001_abc123", "pgTid": "tgen_20261001..." }
```

| 필드 | 필수 | 뜻 | 저장 |
| --- | --- | --- | --- |
| `orderId` | 예 | 주문 생성에서 받은 값. 이 결제의 `order_id`와 일치해야 한다 | 대조용 |
| `pgTid` | 예 | PG 결제 승인 식별자. Toss에서는 성공 URL이 돌려준 `paymentKey`다 | `payment.pg_tid` (UNIQUE) |


### 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": { "paymentId": 101, "status": "APPROVED", "displayStatus": "COMPLETED", "amount": 30000, "chargedPoint": 30000 }
}
```

`chargedPoint`는 컬럼이 아니라 `amount`에서 계산한 값이다(1원 = 1포인트). `200`과 `202`는 같은 모양이다. `displayStatus`를 `200`에도 내리고, `chargedPoint`는 승인됐을 때만 값이 있고 `202`에서는 `null`이다.

### 응답 `202`

이 API의 `202`는 "결제 결과를 아직 확정하지 못했다(`UNKNOWN`)"는 뜻이다. 레포 규약의 `202`(응답을 기다리지 않는 요청 수용)와 용도가 다르며, 이 결제 승인 API에 한정한 사용이다.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": { "paymentId": 101, "status": "UNKNOWN", "displayStatus": "CONFIRMING", "amount": 30000, "chargedPoint": null }
}
```

### 오류

| 응답 | 코드 | 언제 |
| --- | --- | --- |
| `200` | | 승인·충전 완료, 또는 이미 승인된 결제를 그대로 반환 |
| `202` | | `UNKNOWN`. 응답의 `paymentId`로 상태 조회를 폴링한다 |
| `409` | `BILLING-017` | `orderId`가 저장된 값과 다름(위변조 의심). `pgTid`가 다른 결제에 이미 쓰인 경우도 이 코드다 |
| `409` | `BILLING-009` | 이미 취소 처리된 주문(`CANCELLED`). 새 주문을 만들어야 한다 |
| `422` | `BILLING-003` | PG 결제 실패·취소. 충전 없음 |
| `503` | `BILLING-007` | 승인 요청이 PG에 가지 않음(연결 실패·서킷 오픈). 같은 주문으로 재시도 가능 |

### 프론트 처리 흐름 (제안)

결제창은 이미 닫힌 뒤이므로 이 흐름은 PG가 되돌려 보낸 성공 URL 페이지(우리 화면)에서 진행한다.

1. 성공 URL 페이지가 열리면 바로 "결제 확인 중…" 로딩을 보여주고 `:confirm`을 호출한다. 서버가 동기 재조회까지 하면 응답이 길어지므로 프론트 HTTP 타임아웃은 서버 처리 시간보다 길게 잡는다.
2. `200`이면 충전 완료, `422`이면 실패 화면으로 이동한다.
3. `202`이면 상태 조회를 2~3초 간격으로 폴링한다. 상한은 약 15~30초다(값은 팀 확정 필요).
4. 상한까지 `CONFIRMING`이면 "결제 확인이 지연되고 있어요. 확정되면 알려드릴게요" 화면으로 `replace` 이동하고(성공 URL의 `paymentKey`가 히스토리에 남지 않게) [결제 내역 보기]를 둔다. 새로고침으로 confirm이 다시 호출돼도 서버가 멱등이라 안전하다. 이 기간에 새 충전은 `BILLING-008`로 막히므로 안내 문구에 적는다.
5. `503 BILLING-007`이면 "잠시 후 다시 시도" 안내와 함께 같은 주문으로 confirm 재호출을 허용한다.
6. `409 BILLING-009`이면 새 주문을 만들어 결제창을 다시 연다.

---

## `GET /v1/owner/payments/{paymentId}`

승인 확정이 `202`(`UNKNOWN`)를 돌려줬을 때 프론트가 확정 여부를 확인한다. **본인 결제만** 조회되고 남의 결제는 `404 BILLING-004`다. 점주 역할은 이미 통과했고 "내 결제 중에 없다"는 뜻이므로 규약의 "권한은 있는데 없을 때만 `404`"에 해당한다(`403`이 아니다).

### 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "paymentId": 101, "orderId": "ord_20261001_abc123", "status": "UNKNOWN", "displayStatus": "CONFIRMING",
    "amount": 30000, "requestedAt": "2026-10-01T10:30:00+09:00", "approvedAt": null
  }
}
```

`status`는 `REQUESTED` / `APPROVED` / `FAILED` / `CANCELLED` / `UNKNOWN`이다. `requestedAt`은 `payment.created_at`이다.

**`displayStatus`.** 점주 화면에는 내부 `status`(특히 `UNKNOWN`)를 그대로 보여주지 않고 이 값을 쓴다. `UNKNOWN`은 "결과 알 수 없음"이 아니라 "결제 확인 중"으로 보여준다. `UNKNOWN`은 관리자 화면(`GET /v1/admin/payments`)에서만 그대로 쓴다.

| 내부 `status` | `displayStatus` | 화면 문구(예시) |
| --- | --- | --- |
| `REQUESTED` | `IN_PROGRESS` | 결제 진행 중 |
| `UNKNOWN` | `CONFIRMING` | 결제 확인 중(중복 결제되지 않으며, 확정되면 알려드립니다) |
| `APPROVED` | `COMPLETED` | 충전 완료 |
| `FAILED` | `FAILED` | 결제 실패 |
| `CANCELLED` | `CANCELLED` | 결제 취소됨 |

---

## `GET /v1/owner/payments`

`UNKNOWN` 결제는 원장에 아무것도 남기지 않아 포인트 변동 내역에 나타나지 않으므로, 점주가 "확인 중"인 결제를 볼 수 있게 결제 단위 목록을 따로 둔다.

```
GET /v1/owner/payments?pageToken=&pageSize=20
```

요청 시각 내림차순 커서 방식이다. 점주용 목록 세 개(결제, 포인트 원장, 환불 요청)는 `pageToken`·`pageSize`·`nextPageToken`을 쓴다. 관리자 결제·환불·대사 불일치 목록은 페이지 번호 방식을 유지한다. 관리자 포인트 원장은 아래 [원장 조회 계약](#get-v1adminpoint-ledger)에 따라 커서를 사용한다.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "items": [
      { "paymentId": 101, "amount": 30000, "status": "UNKNOWN", "displayStatus": "CONFIRMING",
        "failureReason": null, "requestedAt": "2026-10-01T10:30:00+09:00", "approvedAt": null, "canceledAt": null }
    ],
    "nextPageToken": "..."
  }
}
```

- 본인 결제만 내려간다.
- **승인 시도 전에 닫힌 주문(`pg_tid`가 `NULL`)은 목록에서 제외한다.** 결제창을 열었다가 이탈한 주문이 이력에 쌓이지 않게 하기 위해서다. 승인 확정을 시도한 뒤의 결제만 이력으로 본다.
- `status=UNKNOWN` 항목이 있으면 프론트가 "확인 중인 결제가 있어 새 충전이 잠시 제한돼요"를 띄운다.

---

## `POST /webhook/payments`

승인의 **주 경로는 웹훅이 아니다.** 결제창 인증이 끝나면 PG가 성공 URL(우리 프론트 페이지)로 `paymentKey`, `orderId`, `amount`를 붙여 돌려보내고, 프론트가 이를 `:confirm`에 넘기면 서버가 PG 승인 API를 호출해 충전한다. 웹훅은 이 경로가 놓친 결제의 상태를 맞추는 **보조 경로**다. 점주가 승인 전에 창을 닫아 만료됐거나, 서버가 승인 결과를 받지 못한 건이 여기에 해당한다.

PG 서버가 부르는 서버 간 경로라 **프론트는 부르지 않는다.** 웹훅으로 결제가 확정되면 프론트는 결제 상태 조회나 결제 내역에서 바뀐 상태를 본다. 본문 형식, 검증, 응답 규칙은 [billing API 설계](../design/billing/billing-api-design.md#post-webhookpayments)에 있다.

---

## `GET /v1/owner/point-policy`

충전 화면의 최소 충전 금액과 상품 목록 표시에 쓴다. `effective_date`가 오늘 이하인 것 중 가장 최신 행이다.

### 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "minChargeAmount": 10000,
    "chargeProducts": [10000, 30000, 50000, 100000],
    "effectiveDate": "2026-09-01"
  }
}
```

`chargeProducts`는 `point_policy.charge_products`(JSON)를 그대로 내려준다.

**노출 단가와 하루 예산 하한은 이 응답에 없다.** 운영 플랫폼 설정값이 소유하므로 캠페인 등록 화면이 운영 쪽에서 가져온다. FR-046의 "필요 포인트(하루 예산 × 집행 일수)와 잔액 비교, 부족분 표시"도 프론트가 잔액 조회 응답과 운영 설정값으로 계산하고 별도 API를 두지 않는다.

---

# 잔액과 내역

## `GET /v1/owner/points/balance`

### 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": { "balance": 12000, "reservedPoint": 3500, "unspentTotal": 15500, "businessDate": "2026-10-01" }
}
```

| 필드 | 계산 |
| --- | --- |
| `balance` | 원장 `SUM(amount)` (**`DEDUCT` 행 제외**, 부호를 포함한 저장값을 그대로 더한다) |
| `reservedPoint` | 예약 중 포인트 = 오늘 영업일 `RESERVE` 합 − `DEDUCT` 합 − `RELEASE` 합(절댓값 기준, 0 미만이면 0). 해제는 다음 날 00:00이라 오늘 예약분은 하루 종일 여기 남는다. 해제된 몫은 `balance`로 넘어가 이중 계산되지 않는다 |
| `unspentTotal` | `balance + reservedPoint` |

**원장 조회가 실패하면 잔액 대신 `503 BILLING-006`을 내려준다.** 캐시된 값으로 대신 표시하지 않는다(FR-047 예외).

---

## `GET /v1/owner/points/ledger`

```
GET /v1/owner/points/ledger?type=&from=&to=&campaignId=&pageToken=&pageSize=20
```

| 파라미터 | 필수 | 뜻 |
| --- | --- | --- |
| `type` | 아니오 | `CHARGE` / `RESERVE` / `RELEASE` / `REFUND` / `ADJUST`. 반복 지정 가능 |
| `from`, `to` | 아니오 | 기간(KST 날짜) |
| `campaignId` | 아니오 | 캠페인 필터 (`RESERVE`·`RELEASE`·`REFUND`) |
| `pageToken`, `pageSize` | 아니오 | 생성 시각 내림차순 커서. 기본 20건, 최대 100 |

### 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "items": [
      { "ledgerId": 9001, "entryType": "RESERVE", "amount": -10000, "campaignId": 12,
        "businessDate": "2026-10-01", "paymentId": null, "createdAt": "2026-10-01T00:00:05+09:00" },
      { "ledgerId": 8990, "entryType": "CHARGE", "amount": 30000, "campaignId": null,
        "businessDate": null, "paymentId": 101, "createdAt": "2026-09-30T18:20:00+09:00" }
    ],
    "nextPageToken": "..."
  }
}
```

- **`DEDUCT`는 이 목록에 내려주지 않는다.** 노출당 1행이라 초당 최대 1만 건이 쌓인다. 차감은 소진 집계로 본다.
- **`RESERVE`와 `RELEASE`는 별도 유형으로 구분해 표시한다.** FR-047 세부가 "사후 조사로 과오 차감을 되돌린 금액은 조정(`ADJUST`), 예약 해제는 `RELEASE`로 별도 유형으로 표시"를 요구한다. `RELEASE`의 `createdAt`은 보통 다음 날 00:00 직후다. `businessDate`는 해제한 전날이다.
- `serveId`와 변동 전·후 잔액은 점주에게 내리지 않는다. 관리자 포인트 변동 조회 전용이다.

---

## `GET /v1/owner/points/spend-summary`

```
GET /v1/owner/points/spend-summary?groupBy=DAY&from=2026-09-24&to=2026-09-30&campaignId=
```

차감 내역을 캠페인별·일별로 집계한다. `groupBy`는 `DAY`, `CAMPAIGN`, `DAY_CAMPAIGN`이다.

### 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "items": [
      { "date": "2026-09-30", "campaignId": 12, "spentPoint": 9800, "reservedPoint": 10000, "impressionCount": 980 }
    ]
  }
}
```

`spentPoint`는 그 캠페인·영업일의 `DEDUCT` 합 절댓값, `reservedPoint`는 같은 날 `RESERVE` 합이다. 집계 테이블이 아니라 원장에서 계산하므로 `from`~`to`는 **최대 31일**로 제한한다.

`spentPoint`는 그날 예산에서 집행된 금액이고 과오 차감 정정(양수 `ADJUST`)을 빼지 않는다. 정정은 그날 예산이 아니라 점주 잔액으로 돌아가기 때문이다. 정정을 뺀 광고 비용은 `analytics-api-spec.md` 캠페인 리포트의 `spentAmount`(= `chargedAmount − refundedAmount`)다. 그래서 같은 캠페인·날짜라도 정정이 있으면 두 값이 정정액만큼 다르다. 화면은 이 값을 "집행액", 리포트 값을 "광고 비용"처럼 다른 이름으로 보여 준다. 두 값을 한 화면에서 함께 보여 줘야 하면 이 응답에 정정액 필드를 더하는 것을 별도 이슈로 다룬다(구현 계획 G-8).

---

# 환불

환불은 **잔액 전액만** 가능하다. 부분 환불은 없고, 그날 예약된 포인트는 잔액이 아니므로 환불 대상에서 제외된다. 다음 날 00:00 `RELEASE`로 돌아온 뒤에야 환불 대상이 된다(FR-020).

## `GET /v1/owner/refund-requests/preview`

요청 전에 정책과 예상 환불액을 보여준다. 아무것도 기록하지 않는다.

### 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": { "refundablePoint": 12000, "fee": 0, "expectedRefundAmount": 12000,
            "canRequest": false, "blockedReason": "ACTIVE_CAMPAIGN" }
}
```

- `refundablePoint`는 잔액 전액이다.
- `blockedReason`은 `ACTIVE_CAMPAIGN` / `PENDING_REQUEST` / `NO_BALANCE` / `null`이다. 여러 개가 걸리면 `NO_BALANCE`, `ACTIVE_CAMPAIGN`, `PENDING_REQUEST` 순서로 첫 번째 하나만 낸다. `POST`가 검사하는 순서와 같다.
- `fee`는 이 프로젝트에서 `0`으로 고정이다. `expectedRefundAmount`는 `refundablePoint − fee`다. 수수료 정책이 생기면 정책 컬럼을 더하고 이 값을 계산한다.

---

## `POST /v1/owner/refund-requests`

### 요청

```json
{ "reason": "서비스 중단" }
```

| 필드 | 필수 | 뜻 | 저장 |
| --- | --- | --- | --- |
| `reason` | 아니오 | 요청 사유 | `refund_request.reason` |

**금액은 받지 않는다.** 항상 요청 시점 잔액 전액이다.


### 응답 `201`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": { "refundRequestId": 31, "amount": 12000, "fee": 0, "status": "REQUESTED" }
}
```

**원장에는 아무것도 기록하지 않는다.** 승인 시점에 `REFUND`가 기록된다. `refund_request.fee`에는 `NULL`이 아니라 `0`을 저장한다.

---

## `GET /v1/owner/refund-requests`

```
GET /v1/owner/refund-requests?pageToken=&pageSize=20
```

항목은 `refundRequestId`, `amount`, `fee`, `status`(`REQUESTED` / `APPROVED` / `REJECTED` / `COMPLETED`), `reason`, `rejectReason`(`REJECTED`일 때만), `requestedAt`, `processedAt`이다. 대기 중 요청 확인과 처리 결과 확인에 쓴다. `APPROVED`는 현재 내려가지 않는다. mock 환불이라 승인하면 바로 `COMPLETED`가 된다. 실제 송금을 붙이면 쓰므로 값 목록에는 남긴다.

---

# 관리자 결제와 원장 조회

## `GET /v1/admin/payments`

```
GET /v1/admin/payments?from=&to=&status=&searchType=OWNER_ID&keyword=7&page=0&size=20&sortBy=requestedAt&sortDir=desc
```

| 파라미터 | 필수 | 뜻 |
| --- | --- | --- |
| `from`, `to` | 아니오 | `created_at` 기준 기간 |
| `status` | 아니오 | `REQUESTED` / `APPROVED` / `FAILED` / `CANCELLED` / `UNKNOWN` |
| `searchType`, `keyword` | 아니오 | `OWNER_ID` / `ORDER_ID` / `PG_TID`, 모두 정확 일치. `keyword`를 주면 `searchType`은 필수이고 없으면 `400` |
| `page`, `size` | 아니오 | `page`는 0부터. `size`는 기본 20, 최대 100 |
| `sortBy`, `sortDir` | 아니오 | `requestedAt`(기본)·`amount`·`approvedAt` / `desc`(기본)·`asc`. 같으면 `paymentId`가 보조 정렬 |

응답은 `items`와 `page`, `size`, `totalElements`(필터·검색 적용 후 전체 건수)를 담고 `totalPages`는 내리지 않는다. 허용되지 않은 `sortBy`·`searchType`·`status`는 `400`이다.

`approvedAt`으로 정렬하면 승인 전 결제(`NULL`)는 방향과 관계없이 맨 뒤에 둔다. `searchType=OWNER_ID`의 `keyword`가 숫자가 아니면 `400`이다. `from`·`to`는 KST 날짜이고 양끝을 포함한다.

### 응답 `200` (항목)

```json
{
  "paymentId": 101, "orderId": "ord_20261001_abc123", "pgTid": "tgen_20261001...",
  "ownerId": 7, "amount": 30000, "chargedPoint": 30000,
  "status": "UNKNOWN", "failureReason": null,
  "requestedAt": "2026-10-01T10:30:00+09:00", "approvedAt": null, "canceledAt": null,
  "reconciliation": { "status": "PENDING", "attemptCount": 2, "maxRetry": 5,
                      "nextRetryAt": "2026-10-01T10:34:00+09:00", "lastError": "PG timeout" }
}
```

- FR-019 필수 데이터는 결제 ID(= `pgTid`), 점주 ID, 결제 금액, 충전 포인트, 결제 상태, 결제 시각이다.
- `reconciliation`은 재조회 아웃박스(`payment_reconciliation_outbox`) 행이 있을 때만 내려간다. `status`는 `PENDING` / `RESOLVED` / `FAILED_PERMANENT`다.
- 선택 데이터인 실패 사유는 `payment.failure_reason`에서 온다. `payment.pg_cancel_id`는 응답에 내리지 않는다. 환불이 mock이라 PG 취소를 연동하지 않아 채우는 경로가 없다(컬럼은 V1에 남긴다. PG 취소를 연동하게 되면 그때 채운다).

---

## `POST /v1/admin/payments/{paymentId}:recheck`

FR-019는 `UNKNOWN`을 "PG 조회 API로 재확인한 뒤 상태 확정"하라고 한다. 정상 경로는 재조회 워커가 자동으로 하고, 이 API는 **`FAILED_PERMANENT`로 굳은 건을 사람이 다시 확정**하는 용도다. 요청 본문은 없다.

- `UNKNOWN`일 때만 동작한다. 그 외 상태는 `409 BILLING-005`다.
- PG를 한 번 조회해 결과에 따라 승인 확정의 4단계와 같은 전이를 수행한다(`APPROVED`이면 `CHARGE` 기록, 커밋 후 `PointCharged` 발행).
- 응답은 갱신된 결제 항목으로, 결제 내역 조회의 항목과 같은 모양이다.
- 호출은 감사 로그(`PAYMENT_RECHECK`)에 관리자 ID와 결제 ID, 결과를 남긴다. PG가 아직 확정하지 못하면 결제는 `UNKNOWN`으로 남고 아웃박스의 마지막 조회 시각과 오류만 갱신한다.

---

## `GET /v1/admin/point-ledger`

```
GET /v1/admin/point-ledger?ownerId=7&from=2026-09-01&to=2026-09-30&pageSize=20&sortDir=desc
```

**`ownerId`는 필수다.** FR-021이 "점주별" 조회이고, 변동 전·후 잔액을 계산하려면 점주 단위로 누적해야 한다.

커서 방식이다. 기본 크기와 최대 크기는 [공통 규약](./api-spec.md#목록과-페이지네이션)을 따른다.

| 파라미터 | 필수 | 뜻 |
| --- | --- | --- |
| `from`, `to` | **예** | `created_at` 기준 기간(KST, 양끝 포함). **최대 31일**, 초과·역전은 `400`. `DEDUCT`가 노출당 1행이라 기간 없이 열 수 없다 |
| `type` | 아니오 | 원장 유형 |
| `campaignId` | 아니오 | 캠페인 |
| `pageToken` | 아니오 | 이전 응답의 `nextPageToken`을 그대로 보낸다. 생략하거나 비우면 첫 페이지다 |
| `pageSize` | 아니오 | 기본 20, 최대 100. 1 미만이면 20으로 보정하고 100을 넘으면 100으로 보정한다 |
| `sortDir` | 아니오 | `desc`(기본)·`asc`. **정렬 기준은 `created_at`(+`point_ledger_id`) 고정**이다. 잔액이 이 순서의 누적 합이라 다른 필드로 정렬할 수 없다 |

응답은 `items`와 `nextPageToken`을 담는다. `nextPageToken`이 `null`이면 마지막 페이지이며 결과가 없으면 `items`는 빈 배열이다. `page`, `size`, `totalElements`는 반환하지 않는다.

클라이언트는 다음 요청에도 점주, 기간, 유형, 캠페인, 정렬 방향을 동일하게 보낸다. 조건을 바꾸면 토큰을 비우고 첫 페이지부터 조회한다. 토큰의 형식이나 기록 시각을 읽지 못하면 첫 페이지로 처리한다.

검색(`searchType`)은 없다. 점주 상호 검색은 점주 목록에서 찾은 `ownerId`를 넘긴다. 기존 클라이언트는 `page`·`size`를 새 파라미터로 바꾸고 다음 페이지 이동에 `nextPageToken`을 사용해야 한다.

### 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "items": [
      {
        "ledgerId": 9001, "ownerId": 7, "entryType": "RESERVE",
        "balanceBefore": 22000, "amount": -10000, "balanceAfter": 12000,
        "campaignId": 12, "serveId": null, "pgTid": null,
        "source": "RECONCILIATION", "reason": null, "createdAt": "2026-10-01T00:00:05+09:00"
      }
    ],
    "nextPageToken": null
  }
}
```

- **`balanceBefore`·`balanceAfter`는 저장값이 아니다.** `(created_at, point_ledger_id)` 순서로 그 점주의 누적을 계산해 표시한다(**`DEDUCT` 제외**, 잔액 공식과 동일). `DEDUCT` 행의 변동 전·후 잔액은 직전 값을 그대로 둔다.
- FR-021 필수 데이터는 점주 ID, 원장 유형, 변동 전 잔액, 변동 금액, 변동 후 잔액, 일시다. 선택 데이터는 `campaignId`, `serveId`, PG 거래 ID(`pgTid`, `payment_id`로 조인)다.
- 유형은 여섯 가지 전부를 조회하고 표시한다. FR-021 세부의 "예약/예약해제는 표시하지 않음"은 따르지 않는다. 관리자 화면이라 예약과 해제를 봐야 하고, 이 둘을 숨기면 변동 전·후 잔액이 이어지지 않는다. 점주용 FR-047은 `RESERVE`·`RELEASE`를 점주에게 보이되 `DEDUCT`는 내리지 않는다. 이 부분은 FR-021과 다르다. V51 xlsx는 고치지 않으며 **이 문서가 기준이다**.
- FR-021 세부의 기간별(일/주/월) 합계는 원장을 직접 읽어야 하므로 `analytics-api-spec.md`가 아니라 이 문서의 `GET /v1/admin/point-ledger/summary`가 맡는다(정산 테이블은 정산만 읽는다, [도메인 구조와 의존성 설계](../architecture/런치캐치_도메인_구조와_의존성_설계.md)의 규칙 4). 플랫폼 전체 합계는 계속 `analytics-api-spec.md` A-07이다.
- 변동 전·후 잔액 계산은 커서 전환 뒤에도 유지한다.

---

## `GET /v1/admin/point-ledger/summary`

예약액·해제액도 내려주는 점은 FR-021(합계에서 예약/예약해제 숨김)과 다르며, 이 문서가 기준이다. FR-021 세부가 요구하는 "기간(일/주/월), 충전액, 소진액, 조정액, 환불액 표시"를 점주 한 명 단위로 낸다. 예약액과 예약 해제액도 함께 내린다(관리자용이라 표시한다고 정했다).

```
GET /v1/admin/point-ledger/summary?ownerId=7&period=DAILY&from=2026-09-01&to=2026-09-30&sortDir=asc
```

| 파라미터 | 필수 | 뜻 |
| --- | --- | --- |
| `ownerId` | **예** | 점주 |
| `period` | **예** | `DAILY` / `WEEKLY` / `MONTHLY` |
| `from`, `to` | **예** | 기간(KST 날짜, 양끝 포함). 일별 최대 92일, 주별 최대 53주, 월별 최대 13개월. 초과·역전·누락은 `400` |
| `sortDir` | 아니오 | `asc`(기본, 시간순)·`desc` |

구간 규약은 `analytics-api-spec.md`의 시계열 조회와 같다. 구간 하나가 한 행이고, 주 시작은 월요일이며, `from`~`to`에 일부만 걸친 주·월은 그 안의 날짜만 합산한다. 원장 변동이 없는 구간은 금액이 모두 `0`이다. 행이 최대 92개라 페이지·검색·전체 건수가 없다. `label`은 일별 `2026-09-30`, 주별 그 주 월요일, 월별 `2026-09`다.

### 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "ownerId": 7, "period": "DAILY", "from": "2026-09-01", "to": "2026-09-30",
    "items": [
      { "label": "2026-09-30", "chargeAmount": 30000, "spentAmount": 9800,
        "reservedAmount": 10000, "releasedAmount": 200, "refundAmount": 0, "adjustAmount": -1000 }
    ]
  }
}
```

| 필드 | 뜻 | 원장에서 |
| --- | --- | --- |
| `chargeAmount` | 충전액 | `CHARGE` 합 |
| `spentAmount` | 소진액 | `DEDUCT` 합의 절댓값 − 과오 차감 정정(양수 `ADJUST`) 합. 양수 `ADJUST`는 모두 정정으로 본다. 요구사항 명세서 `비즈니스 규칙` 시트 16행과 `analytics-api-spec.md` A-07의 `spentAmount`와 같은 정의다 |
| `reservedAmount` | 예약액 | `RESERVE` 합의 절댓값 |
| `releasedAmount` | 예약 해제액 | `RELEASE` 합 |
| `refundAmount` | 환불액 | `REFUND` 합의 절댓값 |
| `adjustAmount` | 조정액(±) | `ADJUST` 합 |

`spentAmount`가 이미 정정을 뺀 값이므로 `adjustAmount`를 소진액에 다시 더하거나 빼지 않는다. 정정은 소진이 일어난 날이 아니라 정정 대상 영업일(`targetBusinessDate`)의 구간에 들어가므로 그 구간의 `spentAmount`가 0보다 작아질 수 있다.

이 요약은 정정을 정정 대상 영업일의 구간에 넣는다. 분석의 소진액(`analytics-api-spec.md` A-07)과 같은 귀속이라 같은 기간의 두 `spentAmount`가 어긋나지 않는다. 대상 영업일이 없는 조정(캠페인 없는 조정과 `adjust_target_date`를 두기 전에 기록된 행)은 기록한 날의 구간에 넣는다. 조정액 `adjustAmount`도 같은 귀속이다.

### 계산 규칙

- **날짜 귀속:** `RESERVE`·`RELEASE`는 원장의 영업일 구간에, 소진(`DEDUCT`)은 판정 기록(`deduction_result`)의 서빙 영업일 구간에, `CHARGE`·`REFUND`는 기록된 시각의 KST 날짜 구간에, `ADJUST`는 정정 대상 영업일 구간(없으면 기록된 시각의 KST 날짜 구간)에 들어간다. 자정 뒤에 기록된 전날 노출의 차감도 전날 구간에 들어간다.
- `spentAmount`는 `DEDUCT`에서 읽으므로 00:00 해제가 실패하거나 아직 돌지 않은 영업일에도 예약 전체가 아니라 실제 소진만 보인다. 이때 `reservedAmount`는 예약액 그대로이고 `releasedAmount`는 0이다.

서버가 원장에서 이 합계를 계산하는 방법은 [billing API 설계](../design/billing/billing-api-design.md#get-v1adminpoint-ledgersummary)에 있다.

---

# 관리자 환불 처리

## `GET /v1/admin/refund-requests`

```
GET /v1/admin/refund-requests?status=REQUESTED&ownerId=&from=&to=&page=0&size=20&sortBy=requestedAt&sortDir=desc
```

`page`는 0부터, `size`는 기본 20·최대 100, `sortBy`는 `requestedAt`(기본)·`amount`·`processedAt`, `sortDir`은 `desc`(기본)·`asc`다. 응답은 `page`, `size`, `totalElements`를 담고 `totalPages`는 내리지 않는다.

`processedAt`으로 정렬하면 처리 전(`NULL`) 요청은 방향과 관계없이 맨 뒤에 둔다. `ownerId`와 `status`는 정확 일치 필터다.

항목은 `refundRequestId`, `ownerId`, `amount`, `fee`, `status`, `reason`, `rejectReason`, `requestedAt`, `processedAt`, `processedBy`다. 처리 화면에 필요한 점주 현재 잔액(`currentBalance`)과 집행 중 캠페인 여부(`hasActiveCampaign`)를 함께 내려준다. 이 이름과 `blockedReason=ACTIVE_CAMPAIGN`의 "활성"은 `ACTIVE`만이 아니라 `SCHEDULED`·`PAUSED`를 포함한 "집행 중"을 뜻한다(캠페인 쪽 `hasLiveCampaign`과 같은 범위).

---

## `POST /v1/admin/refund-requests/{refundRequestId}:approve`

`auth-api-spec.md`(저장소 `docs/api-spec/`)의 `:refresh`와 같이 **서버가 규칙에 따라 수행하는 동작**이라 커스텀 메서드로 둔다. 본문은 없다.

요청이 없으면 `404 BILLING-014`, 잠금 대기로 처리하지 못하면 `503 BILLING-006`이다. 재시도할 수 있으나 응답을 반드시 5초 안에 받는다는 보장은 없다.

승인할 때 다음 조건을 다시 확인한다.

1. 대기 중(`REQUESTED`) 요청이어야 한다. 아니면 `409 BILLING-015`다.
2. 집행 중 캠페인이 없어야 한다. 있으면 `409 BILLING-010`이다.
3. 현재 잔액이 요청 금액 이상이어야 한다. 아니면 `409 BILLING-013`이다.

성공하면 잔액에서 요청 금액을 차감하고 요청을 `COMPLETED`로 바꾼다. 현재 환불은 mock이므로 실제 PG 취소나 계좌 송금은 수행하지 않는다. 서버의 잠금 순서와 원장 기록은 [billing API 설계](../design/billing/billing-api-design.md#관리자-환불-승인과-반려)를 따른다.

### 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": { "refundRequestId": 31, "status": "COMPLETED", "amount": 12000, "fee": 0,
            "processedAt": "2026-10-01T11:00:00+09:00" }
}
```

포인트는 `amount`만큼 차감된다. 환불 금액은 `amount - fee`이고 수수료는 `0`이다. 이 응답이 실제 송금 완료를 뜻하지는 않는다.

---

## `POST /v1/admin/refund-requests/{refundRequestId}:reject`

### 요청

```json
{ "rejectReason": "약관상 환불 불가 기간" }
```

| 필드 | 필수 | 뜻 | 저장 |
| --- | --- | --- | --- |
| `rejectReason` | 예 | 반려 사유 | `refund_request.reject_reason` |

`REQUESTED`만 반려할 수 있다(아니면 `409 BILLING-015`). 상태를 `REJECTED`로 바꾸고 처리자·시각을 기록한다. **원장은 건드리지 않는다.** 반려 사유는 감사 로그 전용이 아니라 점주가 환불 요청 목록에서 직접 보는 값이므로 컬럼에 저장하고, DB CHECK가 `REJECTED`일 때 필수임을 강제한다.

---

# 포인트 정책

관리자 포인트 정책은 **최소 충전 금액, 충전 상품, 변경 사유만** 관리한다. 노출 단가와 하루 예산 하한은 운영 플랫폼 설정값이 소유하며 이 API는 수정하지도 응답에 포함하지도 않는다(FR-018, FR-024).

## `GET /v1/admin/point-policy`

```
GET /v1/admin/point-policy            -- 현재 적용 중인 정책과 내일 적용 예정분
GET /v1/admin/point-policy/history?page=0&size=20
```

### 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "current": { "minChargeAmount": 10000, "chargeProducts": [10000, 30000, 50000, 100000],
                 "effectiveDate": "2026-09-01", "changeReason": null, "createdBy": 2 },
    "next": null
  }
}
```

`next`는 이미 저장된 내일 적용분이고 없으면 `null`이다. 이력은 `effective_date` 내림차순이며 과거 행은 수정·삭제할 수 없다.

**이력의 페이지:** 이력은 관리자 표 목록이라 레포 공통 규약(`api-spec.md`)의 커서 대신 페이지 번호 방식을 쓴다. `member.md` 관리자 사용자 목록과 같은 이유이고, 이 API의 예외는 이 문서가 소유한다.

| 파라미터 | 필수 | 뜻 |
| --- | --- | --- |
| `page` | 아니오 | 0부터. 음수면 `400` |
| `size` | 아니오 | 기본 20, 최대 100. 1 미만이면 20으로, 100을 넘으면 100으로 보정한다 |

정렬은 `effectiveDate` 내림차순으로 고정한다. 응답은 `items`와 `page`, `size`, `totalElements`를 담고 `totalPages`는 내리지 않는다. 항목은 위 `current`와 같은 모양이고, 저장된 내일 적용분도 맨 앞에 들어간다.

---

## `PUT /v1/admin/point-policy/next`

`point_policy.effective_date`가 UNIQUE인 **이력형 테이블**이다. 정책 변경은 항상 다음 날 00:00부터 적용되므로 클라이언트가 적용일을 지정하지 않고 서버가 내일 날짜로 고정한다. 내일 적용분을 싱글톤 하위 리소스로 보고 전체를 교체하는 `PUT`이다(같은 날 여러 번 저장하면 마지막 값이 남는다).

### 요청

```json
{ "minChargeAmount": 10000, "chargeProducts": [10000, 30000, 50000, 100000], "reason": "최소 충전액 조정" }
```

| 필드 | 필수 | 검증 | 저장 |
| --- | --- | --- | --- |
| `minChargeAmount` | 예 | 0 초과 | `point_policy.min_charge_amount` |
| `chargeProducts` | 예 | 각 금액이 `minChargeAmount` 이상, 최소 1개(DB CHECK로도 막는다) | `point_policy.charge_products` |
| `reason` | 아니오 | — | `point_policy.change_reason` |

`created_by`는 토큰의 관리자 ID다. 감사 로그에 변경 전·후 값을 기록한다. 응답은 `200`이고 `effectiveDate`가 내일로 내려간다.

응답 본문은 저장된 내일 정책이고 `current`와 같은 모양이다. 두 관리자가 같은 날 동시에 저장해도 오류 없이 나중에 처리된 값이 남는다. 검증에 실패하면 `400 COMMON-002`다. `reason`은 255자까지 받는다.

자동 충전(잔액 하한 도달 시 자동 결제)은 향후 과제라 이 API에 없다.

---

# 조정과 대사 조회

## `POST /v1/admin/point-adjustments`

이중 발급·부정 트래픽처럼 **사후에 확인된 과오 차감을 정정**하는 유일한 경로다. `REFUND`는 방향이 "−"(현금 환불)로 고정돼 있어 "+"로 되돌리는 이 상황과 맞지 않으므로 `ADJUST`(`source=MANUAL`, 양수)로 처리한다.

그래서 양수 조정은 모두 과오 차감 정정으로 보고 소진액에서 뺀다(점주별 합계의 `spentAmount`, `analytics-api-spec.md` A-07). 보상 지급처럼 정정이 아닌 양수 조정은 지원하지 않는다. 그런 용도가 생기면 원장에 조정 종류를 저장하는 변경이 먼저 필요하다.

### 요청

```json
{ "requestId": "3f6c2b1a-...", "ownerId": 7, "amount": 1000,
  "reason": "중복 과금 정정 (serve 8812, 8813)", "campaignId": 12, "targetBusinessDate": "2026-10-13",
  "mismatchId": 55 }
```

멱등 키는 `Idempotency-Key` 헤더가 아니라 본문의 `requestId`다. 레포 공통 규약(`api-spec.md` 「멱등성」)이 별도 헤더를 두지 않기로 정했기 때문이다. 본문에 요청 식별자를 두는 모양은 레포 API 가이드의 `API-5-07`(AIP-155)과 같다.

| 필드 | 필수 | 뜻 | 저장 |
| --- | --- | --- | --- |
| `requestId` | 예 | 요청 식별자. 관리자 화면이 조정 폼을 열 때 만든 UUID를 쓴다. 비었거나 64자를 넘으면 `400` | `point_ledger.idempotency_key` |
| `ownerId` | 예 | 점주. 없는 점주면 `404 BILLING-019`다. 점주 도메인의 상태 조회로 확인한다 | `point_ledger.owner_id` |
| `amount` | 예 | 0이 아닌 정수. 양수면 잔액 증가 | `point_ledger.amount` |
| `reason` | 예 | 사유. 비어 있으면 `400`(DB CHECK로도 막는다) | `point_ledger.reason` |
| `campaignId` | 아니오 | 어느 캠페인의 정정인지 남긴다. 정산 안의 계산(00:00 해제, 대사, 진행률, 점주 소진 요약)에는 넣지 않고, `analytics-api-spec.md` 캠페인 리포트의 `refundedAmount`에는 들어간다. `mismatchId`가 캠페인 대상 건(`CAMPAIGN_SPEND_VS_LEDGER`, `CAMPAIGN_OVERSPEND`)이면 **필수**이고 그 건의 대상 캠페인과 같아야 한다. `PG_VS_CHARGE` 건이거나 `mismatchId`가 없으면 선택이다. 보내면 `targetBusinessDate`도 보내야 한다 | `point_ledger.campaign_id` |
| `targetBusinessDate` | 조건부 | 정정이 가리키는 원래 영업일(`YYYY-MM-DD`). `campaignId`를 보내면 **필수**이고 보내지 않으면 보낼 수 없다. 오늘(KST) 이후면 `400`. 캠페인 대상 대사 건을 닫을 때는 그 건의 `businessDate`와 같아야 한다. 분석의 소진액(`DEDUCT` 합 − 양수 `ADJUST` 합)에서 이 정정은 기록한 날이 아니라 이 영업일에 붙는다. 과거 하한과 그 영업일의 예산 계획 존재는 검사하지 않는다 | `point_ledger.adjust_target_date` |
| `mismatchId` | 아니오 | 대사 불일치 건. 있으면 그 건의 `resolution_type`을 `OVERCHARGE_REVERSAL`로 닫는다. 그 건이 `ownerId`의 것인지 확인한다. `PG_VS_CHARGE`는 대상 결제의 점주, `CAMPAIGN_SPEND_VS_LEDGER`는 대상 캠페인의 점주와 견준다. 없으면 `404 BILLING-020`, 이미 해결됐으면 `409 BILLING-021`, 다른 점주의 건이면 `409 BILLING-022`다 | 원장에 저장하지 않는다. 감사 로그에 남긴다 |

### 멱등

`requestId`를 `point_ledger.idempotency_key`에 저장하고 `UNIQUE(owner_id, idempotency_key)`가 중복 기록을 막는다. `requestId`가 없으면 `400`이다(DB 컬럼은 NULL을 허용하지만 이 API는 필수로 받는다).

- 같은 점주·같은 키로 **내용이 같은** 요청이 다시 오면 새로 기록하지 않고 처음 결과를 `200`으로 돌려준다.
- 같은 키로 `amount`·`campaignId`·`targetBusinessDate`·`reason`이 다르면 `409 BILLING-018`.
- `mismatchId`는 원장에 저장하지 않으므로 이 비교에 들어가지 않는다. 같은 키로 대사 건만 바꿔 보내면 처음 결과를 돌려주고 새 대사 건은 닫히지 않는다. 화면은 조정 폼을 열 때마다 새 `requestId`를 만든다. 어느 조정이 어느 대사 건을 닫았는지는 감사 로그에 남는다.

### 응답

처음 기록하면 `201`, 같은 요청을 다시 받으면 같은 본문으로 `200`이다.

```json
{ "ledgerId": 9101, "ownerId": 7, "amount": 1000, "campaignId": 12, "targetBusinessDate": "2026-10-13",
  "reason": "중복 과금 정정 (serve 8812, 8813)", "createdAt": "2026-10-14T11:20:00+09:00" }
```

`targetBusinessDate`는 `campaignId`가 없는 조정이면 `null`이다.

### 오류

| 응답 | 코드 | 언제 |
| --- | --- | --- |
| `400` | `COMMON-002` | `requestId`·`ownerId`·`amount`·`reason`이 없거나, `amount`가 0이거나, `reason`이 255자를 넘거나, `mismatchId`가 있는데 `amount`가 음수임(과오 차감 정정은 양수다), 또는 캠페인 대상 대사 건(`CAMPAIGN_SPEND_VS_LEDGER`, `CAMPAIGN_OVERSPEND`)인데 `campaignId`가 없거나 대상 캠페인과 다름, `campaignId`와 `targetBusinessDate`의 짝이 맞지 않음, `targetBusinessDate`가 오늘(KST) 이후임, 캠페인 대사 건인데 `targetBusinessDate`가 그 건의 `businessDate`와 다름 |
| `404` | `BILLING-019` | 점주를 찾을 수 없음 |
| `404` | `BILLING-020` | 대사 불일치 건을 찾을 수 없음 |
| `409` | `BILLING-016` | 조정 후 잔액이 음수가 됨 |
| `409` | `BILLING-018` | 같은 키로 내용이 다른 요청 |
| `409` | `BILLING-021` | 이미 해결된 대사 불일치 건 |
| `409` | `BILLING-022` | 대사 불일치 건이 요청한 점주의 것이 아님 |
| `503` | `BILLING-006` | 점주 단위 잠금 대기로 처리하지 못함. 잠시 후 같은 `requestId`로 다시 보내면 된다 |

---

## `GET /v1/admin/settlement-mismatches`

```
GET /v1/admin/settlement-mismatches?checkType=&resolved=false&from=&to=&page=0&size=20&sortBy=createdAt&sortDir=desc
```

`page`는 0부터, `size`는 기본 20·최대 100, `sortBy`는 `createdAt`(기본)·`businessDate`, `sortDir`은 `desc`(기본)·`asc`다. 응답은 `page`, `size`, `totalElements`를 담고 `totalPages`는 내리지 않는다.

**페이지 방식의 근거:** 관리자 표 목록이라 레포 공통 규약(`api-spec.md`)의 커서 대신 페이지 번호 방식을 쓴다(`member.md` 관리자 사용자 목록과 같은 이유). 이 목록은 특히 `totalElements`를 매출 현황의 불일치 건수로 쓰므로(아래) 전체 건수를 주지 않는 커서 방식으로 바꿀 수 없다. 이 API의 예외는 이 문서가 소유한다. 같은 `createdAt`(또는 `businessDate`)이면 `mismatchId`가 보조 정렬이다.

`from`~`to`는 대사 대상 영업일(`businessDate`) 기준이다(KST, 양끝 포함). 정렬 이름은 `detectedAt`이 아니라 `createdAt`(첫 탐지 시각)이다. 같은 `(checkType, targetId, businessDate)`를 다시 탐지하면(`CAMPAIGN_SPEND_VS_LEDGER`는 00:00 전일분, `PG_VS_CHARGE`는 01:30 전일분이며 수동 재실행도 있다) 새 건을 만들지 않고 기존 건을 갱신한다. 그래서 같은 대상, 같은 영업일의 건은 목록에 하나만 있다.

### 응답 `200` (항목)

```json
{
  "mismatchId": 55, "checkType": "CAMPAIGN_SPEND_VS_LEDGER", "targetId": 12,
  "businessDate": "2026-10-01", "expectedValue": 9800, "actualValue": 9790, "diff": -10,
  "resolutionType": null, "createdAt": "2026-10-01T13:35:00+09:00", "jobExecutionId": 301,
  "resolvedAt": null, "resolvedBy": null
}
```

- `checkType`은 `PG_VS_CHARGE` / `CAMPAIGN_SPEND_VS_LEDGER` / `CAMPAIGN_OVERSPEND` 3종이다. `CAMPAIGN_OVERSPEND`는 캠페인의 그날 원장 `DEDUCT` 합이 그날 예약액을 넘은 건이고 `targetId`는 캠페인 ID, `expectedValue`는 예약액, `actualValue`는 `DEDUCT` 합이다. V5. 해제가 끝난 날은 `DEDUCT` 합과 `RELEASE` 합의 합이다.
- `targetId`는 `checkType`에 따라 결제 ID 또는 캠페인 ID다. `diff`는 `actualValue - expectedValue`로 계산한다.
- `resolved=false`는 `resolution_type`이 `NULL`인 미해결 건이다. `resolutionType`은 `CACHE_REBUILD` / `LEDGER_CORRECTION` / `OVERCHARGE_REVERSAL`이다.
- 자동 복구(`CACHE_REBUILD`, `LEDGER_CORRECTION`)는 배치가 처리하므로 별도 해결 API를 두지 않는다. 사람이 처리하는 `OVERCHARGE_REVERSAL`은 포인트 수동 조정의 `mismatchId`로 닫는다.

관리자 화면이 매출 현황(`analytics-api-spec.md` A-07)과 함께 쓸 때, **불일치 건수는 이 API의 `totalElements`로 센다.** 분석 조회 API는 정산 테이블을 읽지 못하기 때문이다([도메인 구조와 의존성 설계](../architecture/런치캐치_도메인_구조와_의존성_설계.md)의 규칙 4).

---

# 오류 코드

`BILLING-` 코드를 이 문서가 소유한다.

| 코드 | 상태 | 언제 |
| --- | --- | --- |
| `BILLING-001` | `400` | 최소 충전 금액 미만 |
| `BILLING-002` | `400` | 충전 상품에 없는 금액 |
| `BILLING-003` | `422` | PG 결제 실패·취소(충전 없음) |
| `BILLING-004` | `404` | 결제를 찾을 수 없음 |
| `BILLING-005` | `409` | 재조회 대상 아님(`UNKNOWN`이 아님) |
| `BILLING-006` | `503` | 원장 조회 실패(캐시로 대신 표시하지 않음) |
| `BILLING-007` | `503` | 승인 요청이 PG에 가지 않음(연결 실패·서킷 오픈). 같은 주문으로 재시도 가능 |
| `BILLING-008` | `409` | 확인 중(`UNKNOWN`)인 결제가 있어 새 충전 주문을 만들 수 없음 |
| `BILLING-009` | `409` | 이미 취소 처리된(`CANCELLED`) 주문이라 승인 확정을 할 수 없음 |
| `BILLING-010` | `409` | 집행 중인 캠페인이 있어 환불 불가 |
| `BILLING-011` | `409` | 처리 대기 중인 환불 요청이 이미 있음 |
| `BILLING-012` | `422` | 환불 가능한 잔액이 없음 |
| `BILLING-013` | `409` | 잔액이 요청 금액보다 부족(환불 후 음수 불가) |
| `BILLING-014` | `404` | 환불 요청을 찾을 수 없음 |
| `BILLING-015` | `409` | 이미 처리된 환불 요청 |
| `BILLING-016` | `409` | 조정 후 잔액이 음수가 됨 |
| `BILLING-017` | `409` | 승인 확정 요청의 `orderId`가 저장된 주문과 다름, `pgTid`가 다른 결제에 이미 쓰임, 또는 `REQUESTED` 결제에 먼저 기록된 `pgTid`와 다른 값으로 승인을 요청함 |
| `BILLING-018` | `409` | 같은 `requestId`로 내용이 다른 조정 요청 |
| `BILLING-019` | `404` | 조정 대상 점주를 찾을 수 없음 |
| `BILLING-020` | `404` | 대사 불일치 건을 찾을 수 없음 |
| `BILLING-021` | `409` | 이미 해결된 대사 불일치 건 |
| `BILLING-022` | `409` | 대사 불일치 건이 조정 대상 점주의 것이 아님 |
| `BILLING-023` | `403` | 점주 상태가 `ACTIVE`가 아님. 점주 결제·포인트·환불 API에 해당한다 |


---
