# 쿠폰 API 명세

공통 규약은 [런치캐치 API 공통 규약](./README.md), 인증은 [auth 명세](./auth.md)를 따른다.
스냅샷 저장 구조, 성능 목표와 미확정 업무 정책은 [설계서](./coupon-api-design.md)에 둔다.
매장 목록, 검색, 도보 경로는 store 담당자에게 별도로 전달했으며 이 문서의 범위에서 제외한다.
쿠폰 규칙과 공통 요청 형식 오류는 구분하며, 인증 오류를 COUPON 코드로 편입하지 않는다.

## 목록

| 메서드 | 경로 | 하는 일 | 권한 | 행 |
| --- | --- | --- | --- | --- |
| POST | `/v1/coupons/{memberCouponId}:generateQr` | 내 쿠폰의 QR 생성, 갱신 | MEMBER | 85 |
| POST | `/v1/coupons` | 선착순 쿠폰 발급 | MEMBER | 83 |
| GET | `/v1/coupons/issuance-limit` | 당일 발급 한도 조회 | MEMBER | 83 |
| POST | `/v1/coupon-events:getStatus` | 매장별 오늘 쿠폰 이벤트, 발급 가능 상태 일괄 조회 | MEMBER | 74, 75, 83 |
| GET | `/v1/coupons` | 내 쿠폰 목록 조회 | MEMBER | 84 |
| GET | `/v1/coupons/{memberCouponId}` | 내 쿠폰 단건 조회 | MEMBER | 84 |
| GET | `/v1/coupons/summary` | 마이페이지 쿠폰 요약 조회 | MEMBER | 91 |
| DELETE | `/v1/coupons/{memberCouponId}` | 미사용 쿠폰 삭제 | MEMBER | - |
| POST | `/v1/owner/coupon-usages` | 점주가 QR을 스캔해 쿠폰 사용 확정, 할인액 확인 | OWNER | 58, 85 |
| GET | `/v1/owner/stores/{storeId}/coupon-usages` | 매장 쿠폰 사용 이력 조회 | OWNER | 59 |

## 오류 코드

공통 요청 오류와 인증 오류는 기존 프로젝트 코드를 재사용한다.
COMMON 전체 정의는 공통 규약, AUTH 전체 정의는 auth 명세를 따른다.
검증 가능한 요청 값의 위반은 `COMMON-002`, 요청을 읽지 못하거나 필수 쿼리가 빠진 경우는 `COMMON-003`이다.
매장 오류 `STORE-001`, `STORE-002`는 store 도메인 소유이며 코드와 메시지는 store 담당자의 최종 명세에 맞춘다.

```json
{
  "code": "COUPON-008",
  "message": "쿠폰이 모두 소진되었습니다.",
  "data": null
}
```

| 코드 | 상태 | 메시지 |
| --- | --- | --- |
| `COUPON-001` | 404 | 쿠폰을 찾을 수 없습니다. |
| `COUPON-002` | 409 | 사용할 수 없는 쿠폰입니다. |
| `COUPON-003` | 403 | 쿠폰을 발급할 수 없는 계정입니다. |
| `COUPON-004` | 403 | 해당 캠페인을 찜한 뒤 발급할 수 있습니다. |
| `COUPON-005` | 404 | 쿠폰 이벤트를 찾을 수 없습니다. |
| `COUPON-006` | 409 | 오늘 이 이벤트에서 이미 쿠폰을 발급받았습니다. |
| `COUPON-007` | 409 | 현재 발급할 수 없는 쿠폰입니다. |
| `COUPON-008` | 409 | 쿠폰이 모두 소진되었습니다. |
| `COUPON-009` | 409 | 오늘 발급 가능한 쿠폰 수를 모두 사용했습니다. |
| `COUPON-010` | 503 | 발급 요청이 몰리고 있습니다. 잠시 후 다시 시도해 주세요. |
| `COUPON-011` | 409 | 이미 사용한 쿠폰은 삭제할 수 없습니다. |
| `COUPON-012` | 403 | 해당 매장에서 사용할 수 없는 쿠폰입니다. |
| `COUPON-013` | 403 | 현재 위치에서 사용할 수 없습니다. |
| `COUPON-014` | 409 | QR이 만료되었거나 유효하지 않습니다. |
| `COUPON-015` | 403 | 해당 쿠폰에 접근할 권한이 없습니다. |
| `COUPON-016` | 409 | 이미 사용된 쿠폰입니다. |
| `COUPON-017` | 403 | 쿠폰을 사용할 수 없는 계정입니다. |

역할 인가 뒤 본인 쿠폰 범위에서 조회한다. 해당 범위에 없으면 대상의 실제 존재 여부와 관계없이
`COUPON-015`를 반환한다. 본인 소유로 확인된 쿠폰을 후속 처리에서 찾지 못한 경우만 `COUPON-001`을 사용한다.

`COUPON-010`은 사용자 호출 빈도 제한이 아니라 서버의 일시적 처리 불가다.
응답에 `Retry-After: 1`을 붙인다. 클라이언트는 이를 지키고 지터를 포함한 제한된 재시도를 수행한다.
소진, 중복 발급, 일일 한도 같은 업무 거절은 자동 재시도하지 않는다.

## 회원 API

### QR 생성

`POST /v1/coupons/{memberCouponId}:generateQr`

사용자가 보유한 쿠폰을 사용하기 위해 QR 생성 요청을 보낸다.

QR 토큰의 유효기간은 생성 시점부터 60초다.
생성 또는 갱신이 성공하면 이전 QR은 즉시 무효화된다. QR 이미지에 담기는 값이 `qrToken`이다.
QR 응답은 `Cache-Control: no-store`로 보낸다. 로그인 토큰과 QR 토큰은 로그에 남기지 않는다.
동시 갱신에서는 마지막으로 반영된 토큰 하나만 유효하다.
사용 가능 시간과 QR 유효기간 중 먼저 끝나는 조건을 적용한다.
QR 생성·갱신 시 쿠폰 소유 회원의 현재 상태가 `ACTIVE`인지 별도로 확인한다. `SUSPENDED`, `WITHDRAWN`이면 `403 COUPON-017`로 거절한다.

**파라미터**

| 위치 | 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| 경로 | `memberCouponId` | integer | O | QR을 생성할 본인 쿠폰 ID |

요청 본문 없음.

**200 OK**

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "qrImageBytes": "iVBORw0KGgoAAAANSUhEUg...",
    "expireTime": "2026-09-30T11:01:00+09:00"
  }
}
```

**응답 필드**

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `qrImageBytes` | string(Base64) | QR 이미지의 PNG 바이트를 Base64로 인코딩한 문자열 |
| `expireTime` | string(date-time) | 이번 QR 토큰의 만료 시각. 갱신하면 이전 QR은 무효화됨 |

**오류**

- 400 `COMMON-002` - 경로, 쿼리, 요청 본문의 필수값이나 형식이 올바르지 않음
- 400 `COMMON-003` - JSON 해석, 타입 변환에 실패하거나 필수 쿼리 파라미터가 없음
- 401 `AUTH-005` - accessToken 쿠키가 없거나 만료, 검증에 실패함
- 403 `AUTH-006` - 인증된 계정의 역할에 이 API 호출 권한이 없음
- 403 `COUPON-015` - 본인 쿠폰에 대한 요청이 아님
- 403 `COUPON-017` - 쿠폰 소유 회원의 현재 상태가 ACTIVE가 아님
- 404 `COUPON-001` - 본인 쿠폰을 찾을 수 없음
- 409 `COUPON-002` - 쿠폰이 이미 사용, 삭제됐거나 사용 가능 시간이 아님

### 쿠폰 발급

`POST /v1/coupons`

사용자가 찜한 가게의 선착순 쿠폰 발급을 요청한다.
발급되는 쿠폰의 가게·메뉴·가격·할인 조건은 해당 이벤트 생성 시 확정한 스냅샷을 따른다.
같은 이벤트에서 발급된 쿠폰은 같은 혜택 정보를 공유하며, 발급 요청마다 현재 캠페인이나 메뉴 정보로 대체하지 않는다.
발급 시간은 시스템에서 정한 시간을 사용한다. 쿠폰 사용 가능 시간을 발급 시간으로 대신 사용하지 않는다.
발급 시 회원의 현재 상태가 `ACTIVE`인지 확인한다. `SUSPENDED`, `WITHDRAWN`이면 `403 COUPON-003`으로 거절한다. 온보딩 완료 여부는 회원 상태와 별도이며 `ONBOARDING`을 회원 상태값으로 사용하지 않는다.

**파라미터**

| 위치 | 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| 본문 | `eventId` | integer | O | 발급받을 당일 쿠폰 이벤트 ID |

```json
{
  "eventId": 501
}
```

사용자 ID, 발급 시각, 영업일은 요청에서 받지 않고 인증 정보와 서버 시계로 결정한다.

**201 Created**

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "memberCouponId": 1001,
    "issueTime": "2026-09-30T11:00:00+09:00"
  }
}
```

**응답 필드**

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `memberCouponId` | integer | 새로 발급된 사용자 쿠폰 ID |
| `issueTime` | string(date-time) | 쿠폰이 발급된 시각 |

**오류**

- 400 `COMMON-002` - 경로, 쿼리, 요청 본문의 필수값이나 형식이 올바르지 않음
- 400 `COMMON-003` - JSON 해석, 타입 변환에 실패하거나 필수 쿼리 파라미터가 없음
- 401 `AUTH-005` - accessToken 쿠키가 없거나 만료, 검증에 실패함
- 403 `AUTH-006` - 인증된 계정의 역할에 이 API 호출 권한이 없음
- 403 `COUPON-003` - 사용자 상태가 쿠폰 발급을 허용하지 않음
- 403 `COUPON-004` - 해당 캠페인에 대한 현재 유효한 찜이 없어 발급 자격이 없음
- 404 `COUPON-005` - 요청한 쿠폰 이벤트가 없음
- 409 `COUPON-006` - 오늘 해당 쿠폰 이벤트에서 이미 쿠폰을 발급받음
- 409 `COUPON-007` - 이벤트가 발급 시작 전, 종료, 중단 등으로 발급 불가 상태임
- 409 `COUPON-008` - 발급 가능한 쿠폰 재고가 모두 소진됨
- 409 `COUPON-009` - 오늘 전체 쿠폰 발급 한도 3장에 도달함
- 503 `COUPON-010` - 다른 발급 요청이 재고를 처리 중이므로 재시도 필요

같은 이벤트와 사용자에 대한 발급은 한 번만 반영한다. 중복 요청은 `409 COUPON-006`이다.
별도 멱등성 헤더는 사용하지 않는다. 캠페인의 같은 영업일에 이벤트가 여러 개 생겨도 중복 발급이 되지 않도록 보장한다.
`201`은 쿠폰 발급과 재고 차감, 일일 발급 횟수 반영이 모두 완료된 뒤 반환한다.
응답을 받지 못해 재시도한 요청에 `COUPON-006`이 오면 해당 이벤트에서 이미 발급받았다는 의미다. 현재 보유한 쿠폰은 내 쿠폰 목록에서 확인한다.

### 당일 쿠폰 발급 한도 조회

`GET /v1/coupons/issuance-limit`

오늘 사용자가 쿠폰을 몇 개 발급받았는지 확인한다.
당일 쿠폰 발급 한도는 3장으로 협의했다.

**파라미터**: 없음.

**200 OK**

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "date": "2026-09-30",
    "issuedCount": 3,
    "dailyLimit": 3,
    "dailyLimitReached": true
  }
}
```

**응답 필드**

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `date` | string(date) | 발급 횟수를 집계한 영업일 |
| `issuedCount` | integer | 해당 영업일에 발급받은 누적 쿠폰 수. 사용하거나 삭제한 쿠폰도 포함 |
| `dailyLimit` | integer | 해당 영업일에 발급받을 수 있는 최대 쿠폰 수 |
| `dailyLimitReached` | boolean | `issuedCount >= dailyLimit`이면 `true` |

**오류**

- 401 `AUTH-005` - accessToken 쿠키가 없거나 만료, 검증에 실패함
- 403 `AUTH-006` - 인증된 계정의 역할에 이 API 호출 권한이 없음

### 매장별 쿠폰 이벤트 상태 조회

`POST /v1/coupon-events:getStatus`

여러 매장의 ID를 받아 각 매장의 쿠폰 이벤트 상태를 조회한다.
존재하는 매장만 입력 순서대로 매장당 한 항목을 반환한다. 고정된 ID 목록의 일괄 조회이므로 페이지네이션을 적용하지 않는다.
존재하지 않는 매장은 응답에서 제외하며, 입력한 매장이 모두 없으면 `200`과 빈 `items`를 반환한다.
오늘 이벤트 조회는 DB 상태가 `ACTIVE` 또는 `SCHEDULED`인 이벤트만 대상으로 하며 `PAUSED`, `ENDED` 이벤트는 제외한다.
존재하는 매장에 조회 대상인 오늘 이벤트가 없으면 해당 매장을 제외하지 않고 `NO_EVENT` 항목을 반환한다.
이벤트가 있으면 사용 가능 시간은 해당 이벤트 생성 시 확정한 스냅샷의 시간을 반환한다.
`canIssue`는 조회 시점의 안내이며 재고를 예약하지 않는다. 실제 발급 요청에서는 자격과 재고를 다시 확인한다.

**파라미터**

| 위치 | 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| 본문 | `storeIds` | `array<integer>` | O | 양의 정수인 매장 ID 목록. 1개 이상 100개 이하, 중복 불가 |

```json
{
  "storeIds": [10, 20, 30]
}
```

**200 OK**

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "serverTime": "2026-09-30T12:00:00.123+09:00",
    "items": [
      {
        "storeId": 10,
        "hasCampaign": true,
        "eventId": 501,
        "eventState": "OPEN",
        "remainingQuantity": 25,
        "usableStartTime": "2026-09-30T11:00:00+09:00",
        "usableEndTime": "2026-09-30T14:00:00+09:00",
        "hasIssuedToday": false,
        "wished": true,
        "canIssue": true,
        "cannotIssueReason": null
      },
      {
        "storeId": 20,
        "hasCampaign": true,
        "eventId": 502,
        "eventState": "SOLD_OUT",
        "remainingQuantity": 0,
        "usableStartTime": "2026-09-30T11:00:00+09:00",
        "usableEndTime": "2026-09-30T14:00:00+09:00",
        "hasIssuedToday": true,
        "wished": true,
        "canIssue": false,
        "cannotIssueReason": "ALREADY_ISSUED"
      },
      {
        "storeId": 30,
        "hasCampaign": true,
        "eventId": null,
        "eventState": "NO_EVENT",
        "remainingQuantity": 0,
        "usableStartTime": null,
        "usableEndTime": null,
        "hasIssuedToday": false,
        "wished": false,
        "canIssue": false,
        "cannotIssueReason": "NO_EVENT"
      }
    ]
  }
}
```

**응답 필드**

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `serverTime` | `string(date-time)` | 요청 처리 시작 시 서버 시계로 한 번 읽은 현재 시각. 한국 시간(Asia/Seoul)의 ISO 8601 문자열이며 밀리초 세 자리와 `+09:00` 오프셋을 포함한다. `data`에 한 번 반환하며 `null`을 허용하지 않음 |
| `items` | `array<object>` | 매장별 이벤트 상태 목록 |
| `storeId` | `integer` | 매장 ID |
| `hasCampaign` | `boolean` | 해당 매장에 `ACTIVE` 또는 `SCHEDULED` 상태의 쿠폰 캠페인이 있는지 |
| `eventId` | `integer` 또는 `null` | 조회 대상인 오늘 이벤트 ID. 없으면 `null` |
| `eventState` | `string(enum)` | 오늘 이벤트의 발급 상태 |
| `remainingQuantity` | `integer` | 조회 시점에 해당 이벤트에서 발급 가능한 미발급 재고 수량. `AVAILABLE` 재고 개수이며 0 이상의 정수. 재고가 없거나 이벤트가 없으면 `0` |
| `usableStartTime` | `string(date-time)` 또는 `null` | 이벤트의 쿠폰 사용 가능 시작 시각. 포함. 한국 시간 ISO 8601 형식(`+09:00`). 이벤트가 없으면 `null` |
| `usableEndTime` | `string(date-time)` 또는 `null` | 이벤트의 쿠폰 사용 가능 종료 시각. 제외. 한국 시간 ISO 8601 형식(`+09:00`). 이벤트가 없으면 `null` |
| `hasIssuedToday` | `boolean` | 사용자가 오늘 이 이벤트 쿠폰을 이미 받았는지 |
| `wished` | `boolean` | 현재 유효한 캠페인 찜을 보유했는지. 이벤트가 없으면 `false` |
| `canIssue` | `boolean` | 조회 시점에 사용자가 발급받을 수 있는지 |
| `cannotIssueReason` | `string` 또는 `null` | 발급할 수 없는 사유 코드. 발급 가능하면 `null`. 전체 값과 우선순위는 아래 표를 따름 |

`eventState ENUM` :

- `SCHEDULED`: 시작 전
- `OPEN`: 발급 중
- `SOLD_OUT`: 품절
- `CLOSED`: 종료
- `NO_EVENT`: 조회 대상인 오늘 이벤트 없음

`eventState`가 `NO_EVENT`이면 `eventId`는 `null`이다. 그 외에는 발급 요청에 사용할 `eventId`를 반환한다.

`cannotIssueReason`은 아래 순서로 첫 번째 해당 사유 하나만 반환한다.

| 우선순위 | 값 | 조건 |
| --- | --- | --- |
| 1 | `NO_EVENT` | 조회 대상인 오늘 이벤트 없음 |
| 2 | `ACCOUNT_NOT_ELIGIBLE` | 계정의 쿠폰 발급이 제한됨 |
| 3 | `ALREADY_ISSUED` | 오늘 이 이벤트에서 이미 발급받음. 삭제 후에도 해당 |
| 4 | `DAILY_LIMIT_REACHED` | 당일 누적 발급 횟수 3건 도달 |
| 5 | `NOT_WISHED` | 해당 캠페인의 유효한 찜 없음 |
| 6 | `NOT_OPEN` | 시스템에서 정한 발급 시간의 시작 전 또는 종료 |
| 7 | `SOLD_OUT` | 발급 가능 시간이지만 재고 없음 |

일시적 요청 혼잡은 조회만으로 예측하지 않으므로 사유 enum에 넣지 않는다.

**오류**

- 400 `COMMON-002` - storeIds가 비었거나 개수, 범위, 중복 규칙에 어긋남
- 400 `COMMON-003` - JSON 해석 또는 타입 변환 실패
- 401 `AUTH-005` - accessToken 쿠키가 없거나 유효하지 않음
- 403 `AUTH-006` - MEMBER 역할이 아님

### 쿠폰 목록·단건 공통 표시 정보

쿠폰 목록과 단건 조회는 같은 이벤트 스냅샷에서 가게·메뉴·가격·할인 조건을 가져와 같은 필드로 반환한다.
목록과 단건 조회 응답에는 `eventId`를 포함하지 않는다. 발급된 쿠폰의 조회·QR 생성·삭제에는 `memberCouponId`를 사용한다.
QR 화면은 단건 조회의 표시 정보와 QR 생성 API의 이미지·만료 시각을 함께 사용한다.

스냅샷은 이벤트 생성 시 확정하며 이후 원본 가게명, 메뉴 가격, 할인 조건이 바뀌어도 변경하지 않는다.
같은 이벤트에서 나중에 발급된 쿠폰도 같은 스냅샷을 사용한다.
QR 사용 시에도 같은 스냅샷을 적용하여 쿠폰에 표시한 혜택과 실제 할인 계산의 기준을 일치시킨다.

- `storeId`, `storeName`, `discountTargetType`, `discountType`, `discountValue`는 필수이며 `null`을 허용하지 않는다.
- `storeImageUrl`은 스냅샷이 참조하는 대표 이미지의 조회용 URL이다. 이미지가 없으면 `null`이다.
- URL이 갱신되더라도 참조하는 이미지 내용은 유지한다.
- `discountTargetType=MENU`이면 `targetMenu`와 그 안의 `menuId`, `name`, `price`, `discountedPrice`가 모두 필수다.
- `discountTargetType=ALL`이면 `targetMenu`를 생략하지 않고 `null`로 반환한다.
- `targetMenu.price`와 `targetMenu.discountedPrice`는 이벤트 생성 시 확정한 할인 전·후 가격(원)이다.
- 할인 후 가격은 0 이상이며 할인 전 가격 이하이다. 계산 정책은 이벤트 생성 전에 확정한다.
- `discountValue`는 `PERCENT`이면 할인율(%), `AMOUNT`이면 할인액(원)이다.
- 지원하는 할인 조합은 `MENU + PERCENT`, `MENU + AMOUNT`, `ALL + AMOUNT`다. `ALL + PERCENT`는 지원하지 않는다.
- `usableStartTime`과 `usableEndTime`은 이벤트에서 확정한 사용 가능 시간이다. 시작 시각은 포함하고 종료 시각은 제외한다.
- `state`는 조회 시점의 쿠폰 상태다. `usable`은 쿠폰 상태가 `ISSUED`이고 조회 시각이 사용 가능 시간 안이면 `true`, 그 외에는 `false`다. 고정된 가게·혜택 정보와 구분한다.
- `usable` 계산과 `usable = true` 목록 필터에는 회원 계정 상태, QR 유효성, 점주 권한과 스캔 위치를 포함하지 않는다. QR 생성·갱신과 사용 확정 시 회원의 현재 `ACTIVE` 상태를 별도로 검증하며, `usable: true`가 최종 사용 성공을 보장하지 않는다.
- `useTime`, `discountAmount`는 사용 완료 결과다. 사용 이력에 확정해 저장한 값을 반환하고 원본 가격 변경으로 재계산하지 않는다.

이미 발급된 쿠폰은 원본 캠페인이 중단·종료되어도 해당 쿠폰의 사용 가능 시간 안에서 QR 생성과 사용을 허용한다.
쿠폰의 사용·삭제 상태, QR 유효성, 현재 회원·점주 계정 상태와 매장 관리 권한 검사는 계속 적용한다.

### 내 쿠폰 목록 조회

`GET /v1/coupons?pageSize=20`

로그인한 사용자의 쿠폰 목록을 조회한다. 삭제된 쿠폰은 모든 필터에서 제외한다.
각 항목의 가게·혜택·메뉴 필드는 [쿠폰 목록·단건 공통 표시 정보](#쿠폰-목록단건-공통-표시-정보)의 스냅샷 규칙을 따른다.

**파라미터**

| 위치 | 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| 쿼리 | `filter` | string | X | 아래에서 정의한 상태 또는 사용 가능 여부 필터. 생략하면 전체 |
| 쿼리 | `pageSize` | integer | X | 기본값 20, 1 이상 100 이하 |
| 쿼리 | `pageToken` | string | X | 이전 응답의 nextPageToken. 생략하면 첫 페이지 |

허용하는 filter 표현식은 다음과 같다. 전송할 때 URL 인코딩한다.

| 기존 조회 범위 | filter |
| --- | --- |
| 전체 | 생략 |
| 사용 가능 | `usable = true` |
| 미사용 | `state = "ISSUED"` |
| 사용 완료 | `state = "USED"` |
| 기간 만료 | `state = "EXPIRED"` |

임의 필드, 복합 표현식, 목록에 없는 값은 지원하지 않으며 `400 COMMON-002`다.
ISSUED는 아직 사용하지 않고 기간이 끝나지 않은 쿠폰이며 사용 시작 전 쿠폰도 포함한다.
EXPIRED는 조회 시점에 사용 기간이 지난 미사용 쿠폰이다.
정렬은 `issueTime DESC, memberCouponId DESC`로 고정한다. 커서에도 두 값을 사용한다.
`filter`를 변경하면 `pageToken`을 비우고 첫 페이지부터 요청한다. 전체 조회로 전환하여 `filter`를 생략하는 경우에도 동일하다.

아래 응답 예시는 2026-09-30 12:00+09:00에 전체 쿠폰을 조회한 결과다.

**200 OK**

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "items": [
      {
        "memberCouponId": 1001,
        "storeId": 10,
        "storeName": "런치식당",
        "storeImageUrl": "https://cdn.example.com/stores/10/logo-v1.jpg",
        "discountTargetType": "MENU",
        "discountType": "AMOUNT",
        "discountValue": 3000,
        "targetMenu": {
          "menuId": 701,
          "name": "숯불갈비 정식",
          "price": 12000,
          "discountedPrice": 9000
        },
        "state": "ISSUED",
        "usable": true,
        "usableStartTime": "2026-09-30T11:00:00+09:00",
        "usableEndTime": "2026-09-30T14:00:00+09:00",
        "useTime": null,
        "discountAmount": null
      },
      {
        "memberCouponId": 1002,
        "storeId": 20,
        "storeName": "갈비식당",
        "storeImageUrl": "https://cdn.example.com/stores/20/logo-v1.jpg",
        "discountTargetType": "MENU",
        "discountType": "PERCENT",
        "discountValue": 20,
        "targetMenu": {
          "menuId": 702,
          "name": "갈비살 정식",
          "price": 15000,
          "discountedPrice": 12000
        },
        "state": "USED",
        "usable": false,
        "usableStartTime": "2026-09-30T10:00:00+09:00",
        "usableEndTime": "2026-09-30T14:00:00+09:00",
        "useTime": "2026-09-30T11:00:30+09:00",
        "discountAmount": 3000
      },
      {
        "memberCouponId": 1003,
        "storeId": 30,
        "storeName": "국수식당",
        "storeImageUrl": null,
        "discountTargetType": "ALL",
        "discountType": "AMOUNT",
        "discountValue": 1000,
        "targetMenu": null,
        "state": "EXPIRED",
        "usable": false,
        "usableStartTime": "2026-09-29T11:00:00+09:00",
        "usableEndTime": "2026-09-29T14:00:00+09:00",
        "useTime": null,
        "discountAmount": null
      }
    ],
    "nextPageToken": null
  }
}
```

**응답 필드**

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `items` | `array<object>` | 선택한 상태의 쿠폰 목록. 없으면 빈 배열 |
| `nextPageToken` | string 또는 null | 다음 페이지 커서. 마지막 페이지면 null |
| `memberCouponId` | `integer` | 사용자 쿠폰 ID |
| `storeId` | `integer` | 이벤트 스냅샷에 고정한 쿠폰 사용 매장 ID |
| `storeName` | `string` | 이벤트 스냅샷에 고정한 가게명 |
| `storeImageUrl` | `string(uri)` 또는 `null` | 스냅샷에 고정한 대표 이미지의 조회용 URL. 이미지가 없으면 `null` |
| `discountTargetType` | `string(enum)` | 스냅샷의 할인 대상. `ALL` 또는 `MENU` |
| `discountType` | `string(enum)` | 스냅샷의 할인 방식. `PERCENT` 또는 `AMOUNT` |
| `discountValue` | `integer` | 스냅샷의 할인율(%) 또는 할인액(원) |
| `targetMenu` | `object` 또는 `null` | 스냅샷의 할인 대상 메뉴. `MENU`이면 필수, `ALL`이면 `null` |
| `targetMenu.menuId` | `integer` | 할인 대상 메뉴 ID |
| `targetMenu.name` | `string` | 스냅샷에 고정한 메뉴명 |
| `targetMenu.price` | `integer` | 스냅샷에 고정한 할인 전 메뉴 가격(원) |
| `targetMenu.discountedPrice` | `integer` | 스냅샷에 고정한 할인 후 메뉴 가격(원) |
| `state` | `string(enum)` | 조회 시점 상태: `ISSUED`, `USED`, `EXPIRED` |
| `usable` | `boolean` | 쿠폰이 ISSUED이고 사용 가능 시간 안이면 true. 회원 계정 상태는 포함하지 않음 |
| `usableStartTime` | `string(date-time)` | 사용 가능 시작 시각 |
| `usableEndTime` | `string(date-time)` | 사용 가능 종료 시각 |
| `useTime` | `string(date-time)` 또는 `null` | `USED`이면 사용 확정 시각, 그 외에는 `null` |
| `discountAmount` | `integer` 또는 `null` | `USED`이면 사용 시점에 확정한 할인액(원), 그 외에는 `null` |

**오류**

- 400 `COMMON-002` - 필터, pageSize 등 쿼리 파라미터가 올바르지 않음
- 400 `COMMON-003` - JSON 해석, 타입 변환에 실패하거나 필수 쿼리 파라미터가 없음
- 401 `AUTH-005` - accessToken 쿠키가 없거나 만료, 검증에 실패함
- 403 `AUTH-006` - 인증된 계정의 역할에 이 API 호출 권한이 없음

### 내 쿠폰 단건 조회

`GET /v1/coupons/{memberCouponId}`

로그인한 사용자가 보유한 쿠폰 한 건을 조회한다. MEMBER 역할과 본인 소유권을 먼저 확인한다.
소유권을 확인할 수 없는 ID는 실제 존재 여부와 관계없이 `403 COUPON-015`를 반환한다.
본인 소유로 확인됐지만 삭제된 쿠폰은 `404 COUPON-001`을 반환한다.
미사용, 사용 완료, 기간 만료 쿠폰은 모두 조회할 수 있다.
가게·혜택·메뉴 정보는 목록과 동일한 이벤트 스냅샷을 사용하며 [공통 표시 정보](#쿠폰-목록단건-공통-표시-정보)를 따른다.

조회는 읽기 전용이다. QR 생성과 갱신은 `POST /v1/coupons/{memberCouponId}:generateQr`로 별도 요청한다.
state와 usable은 목록과 동일하게 조회 시점의 상태와 사용 가능 여부를 나타낸다.
사용 가능 시간은 usableStartTime 이상, usableEndTime 미만이다.

**파라미터**

| 위치 | 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| 경로 | `memberCouponId` | integer | O | 조회할 본인 쿠폰 ID. 양의 정수 |

쿼리 파라미터와 요청 본문 없음.

**200 OK**

아래 응답 예시는 2026-09-30 12:00+09:00에 조회한 결과다.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "memberCouponId": 1001,
    "storeId": 10,
    "storeName": "런치식당",
    "storeImageUrl": "https://cdn.example.com/stores/10/logo-v1.jpg",
    "discountTargetType": "MENU",
    "discountType": "AMOUNT",
    "discountValue": 3000,
    "targetMenu": {
      "menuId": 701,
      "name": "숯불갈비 정식",
      "price": 12000,
      "discountedPrice": 9000
    },
    "state": "ISSUED",
    "usable": true,
    "issueTime": "2026-09-30T11:00:00+09:00",
    "usableStartTime": "2026-09-30T11:00:00+09:00",
    "usableEndTime": "2026-09-30T14:00:00+09:00",
    "useTime": null,
    "discountAmount": null
  }
}
```

**응답 필드**

모든 필드는 출력 전용이다. 목록 항목과 같은 이름의 필드는 같은 의미를 사용한다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `memberCouponId` | integer | 사용자 쿠폰 ID |
| `storeId` | `integer` | 이벤트 스냅샷에 고정한 쿠폰 사용 매장 ID |
| `storeName` | `string` | 이벤트 스냅샷에 고정한 가게명 |
| `storeImageUrl` | `string(uri)` 또는 `null` | 스냅샷에 고정한 대표 이미지의 조회용 URL. 이미지가 없으면 `null` |
| `discountTargetType` | `string(enum)` | 스냅샷의 할인 대상. `ALL` 또는 `MENU` |
| `discountType` | `string(enum)` | 스냅샷의 할인 방식. `PERCENT` 또는 `AMOUNT` |
| `discountValue` | `integer` | 스냅샷의 할인율(%) 또는 할인액(원) |
| `targetMenu` | `object` 또는 `null` | 스냅샷의 할인 대상 메뉴. `MENU`이면 필수, `ALL`이면 `null` |
| `targetMenu.menuId` | `integer` | 할인 대상 메뉴 ID |
| `targetMenu.name` | `string` | 스냅샷에 고정한 메뉴명 |
| `targetMenu.price` | `integer` | 스냅샷에 고정한 할인 전 메뉴 가격(원) |
| `targetMenu.discountedPrice` | `integer` | 스냅샷에 고정한 할인 후 메뉴 가격(원) |
| `state` | string(enum) | 조회 시점 상태: ISSUED, USED, EXPIRED |
| `usable` | boolean | 쿠폰이 ISSUED이고 사용 가능 시간 안이면 true. 회원 계정 상태는 포함하지 않음 |
| `issueTime` | string(date-time) | 쿠폰이 발급된 시각 |
| `usableStartTime` | string(date-time) | 사용 가능 시작 시각. 포함 |
| `usableEndTime` | string(date-time) | 사용 가능 종료 시각. 제외 |
| `useTime` | string(date-time) 또는 null | USED이면 사용 확정 시각, 그 외에는 null |
| `discountAmount` | integer 또는 null | USED이면 사용 시점에 확정한 할인액(원), 그 외에는 null |

**오류**

- 400 `COMMON-002` - memberCouponId가 양의 정수가 아님
- 400 `COMMON-003` - memberCouponId를 정수로 해석할 수 없음
- 401 `AUTH-005` - accessToken 쿠키가 없거나 만료, 검증에 실패함
- 403 `AUTH-006` - MEMBER 역할이 아님
- 403 `COUPON-015` - 본인 쿠폰에 대한 소유권을 확인할 수 없음. 대상의 실제 존재 여부는 구분하지 않음
- 404 `COUPON-001` - 본인 소유로 확인됐지만 삭제됐거나 후속 조회에서 쿠폰을 찾을 수 없음

### 마이페이지 쿠폰 요약 확인

`GET /v1/coupons/summary`

사용자가 지금까지 발급받은 쿠폰 수, 사용한 쿠폰 수, 할인받은 금액을 조회한다.
할인받은 금액은 사용 이력에 확정해 저장한 할인액의 합계이며 현재 캠페인이나 메뉴 가격으로 재계산하지 않는다.

**파라미터**: 없음.

**200 OK**

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "receivedCouponCount": 12,
    "usedCouponCount": 8,
    "totalDiscountAmount": 24000
  }
}
```

**응답 필드**

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `receivedCouponCount` | integer | 지금까지 발급받은 누적 쿠폰 수. 삭제한 쿠폰도 포함 |
| `usedCouponCount` | integer | 지금까지 사용한 쿠폰 수 |
| `totalDiscountAmount` | integer | 사용 이력의 할인액 합계(원) |

**오류**

- 401 `AUTH-005` - accessToken 쿠키가 없거나 만료, 검증에 실패함
- 403 `AUTH-006` - 인증된 계정의 역할에 이 API 호출 권한이 없음

### 쿠폰 삭제

`DELETE /v1/coupons/{memberCouponId}`

사용자가 보유한 미사용 쿠폰을 삭제한다. 소프트 삭제하며 기존 발급 기록은 유지한다.
삭제한 쿠폰은 목록과 QR 생성에서 제외하고 기존 QR도 무효화한다.
삭제해도 일일 발급 횟수와 이벤트 중복 발급 기록은 유지하며 재고를 복원하지 않는다.
이미 삭제된 본인 쿠폰을 다시 삭제하면 `204`로 응답한다. 사용 완료 쿠폰은 삭제할 수 없다.

**파라미터**

| 위치 | 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| 경로 | `memberCouponId` | integer | O | 삭제할 본인 쿠폰 ID |

요청 본문 없음.

**204 No Content**

응답 본문과 응답 봉투가 없다. 클라이언트는 JSON 파싱을 수행하지 않는다.

**오류**

- 400 `COMMON-002` - 경로, 쿼리, 요청 본문의 필수값이나 형식이 올바르지 않음
- 400 `COMMON-003` - JSON 해석, 타입 변환에 실패하거나 필수 쿼리 파라미터가 없음
- 401 `AUTH-005` - accessToken 쿠키가 없거나 만료, 검증에 실패함
- 403 `AUTH-006` - 인증된 계정의 역할에 이 API 호출 권한이 없음
- 403 `COUPON-015` - 본인 쿠폰에 대한 요청이 아님
- 404 `COUPON-001` - 본인 쿠폰을 찾을 수 없음
- 409 `COUPON-011` - 이미 사용한 쿠폰은 삭제할 수 없음

## 점주 API

점주 API는 `OWNER` 역할과 현재 `ACTIVE` 상태를 모두 요구한다.
`ONBOARDING`, `SUSPENDED`, `WITHDRAWN` 상태는 `403`으로 거절한다.
점주 상태 제한 오류의 코드는 OWNER 도메인이 정의하며, 구체 코드는 [미확정 업무 정책](./coupon-api-design.md#미확정-업무-정책)에서 관리한다.

### QR 사용

`POST /v1/owner/coupon-usages`

**파라미터**

| 위치 | 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| 본문 | `longitude` | number(double) | O | QR을 스캔한 위치의 경도 (`-180`~`180`) |
| 본문 | `latitude` | number(double) | O | QR을 스캔한 위치의 위도 (`-90`~`90`) |
| 본문 | `qrToken` | string(UUID) | O | 스캔한 QR 토큰 |

서버는 `qrToken`으로 발급 쿠폰을 찾고, 서버 내부의 이벤트 참조로 스냅샷을 조회하여 사용 매장과 혜택을 확인한다.
할인 대상·방식·값과 대상 메뉴의 할인 전·후 가격은 쿠폰 목록·단건에 표시한 것과 같은 스냅샷을 사용한다.
원본 캠페인의 현재 할인 조건이나 현재 메뉴 가격으로 대체하지 않는다.
매장 관리 권한은 인증된 점주의 현재 권한으로 검증한다.
쿠폰을 새로 사용 확정할 때에는 점주 인증과 별도로 쿠폰 소유 회원의 현재 상태가 `ACTIVE`인지 확인한다. `SUSPENDED`, `WITHDRAWN`이면 `403 COUPON-017`로 거절한다. QR 생성 당시 회원 상태나 조회 응답의 `usable` 값으로 이 검증을 대신하지 않는다.
위치 검사의 기준 좌표는 이벤트 스냅샷의 `storeId`로 매장 정보를 조회해 확인한다. 좌표를 이벤트 스냅샷에 저장하지 않는다.
점주 화면에는 사용 확정 결과의 할인액을 보여준다.
요청에서 주문 금액이나 메뉴 금액을 받지 않으며, 주문/POS의 실제 결제액과 수량은 서버에서 확인하지 않는다.

요청

```json
{
  "longitude": 127.0,
  "latitude": 37.5,
  "qrToken": "6c75d5bc-cae3-4eeb-a573-fc3bcb1673a5"
}
```

**201 Created**

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "memberCouponId": 1001,
    "usageHistoryId": 5001,
    "discountAmount": 3000,
    "useTime": "2026-09-30T12:30:00+09:00"
  }
}
```

**응답 필드**

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `memberCouponId` | integer | 사용 완료된 사용자 쿠폰 ID |
| `usageHistoryId` | integer | 생성된 사용 이력 ID |
| `discountAmount` | integer | 사용 시점에 서버가 확정한 할인액(원) |
| `useTime` | string(date-time) | 쿠폰 사용이 확정된 시각 |

성공 응답 시 쿠폰 사용과 사용 이력 기록이 하나의 트랜잭션으로 완료된 상태다.
서버는 인증된 OWNER가 해당 매장을 관리하는지 먼저 검증하며 사용 가능한 쿠폰과 유효 QR만 처리한다.
같은 QR의 동시 사용은 한 건만 성공한다. 사용 완료된 QR로 다시 요청하면 `409 COUPON-016`과 "이미 사용된 쿠폰입니다."를 반환하며 이력을 추가하지 않는다.
성공 응답을 받지 못해 같은 QR로 재시도한 경우에도 동일하게 응답하며 기존 성공 결과를 다시 반환하지 않는다.
인증과 현재 매장 관리 권한을 확인한 뒤, 사용 완료된 QR인지는 QR 만료 여부보다 먼저 확인한다. 사용 후 QR 만료 시각이 지나도 `COUPON-016`으로 구분한다.
생성 시각 이상, expireTime 미만에서만 QR을 사용할 수 있다. 사용 시간은 usableStartTime 이상, usableEndTime 미만이다.
새 사용 확정의 시간 판정은 쿠폰 잠금 확보 후 서버의 현재 시각을 한 번 읽어 수행하며, 같은 시각을 `useTime`으로 기록한다. 잠금을 기다리는 동안 QR 또는 쿠폰의 사용 가능 시간이 끝났으면 사용을 거절한다.
QR 사용, 삭제, 갱신의 동시 실행은 쿠폰 상태와 QR 버전을 검사해 직렬화한다.
할인액은 사용 시점의 확정값으로 사용 이력에 저장하고 이후 메뉴 가격이 바뀌어도 이력은 재계산하지 않는다.
전체 주문 정액 할인은 스냅샷에 고정한 할인액을 적용하며 전체 주문 정률 할인은 지원하지 않는다.
단, 주문 금액이 스냅샷 할인액보다 작은 경우의 사용 허용 여부, 기록할 할인액과 검증 방식은 미확정이다. 주문 금액 입력 여부를 포함해 구현 전에 확정한다.
메뉴 수량, 최소 주문액 검증과 할인 계산의 세부 정책은 [설계서의 미확정 업무 정책](./coupon-api-design.md#미확정-업무-정책)을 따른다.
스냅샷에 저장할 할인 후 메뉴 가격의 반올림·상한 정책은 이벤트 생성 전에 확정하며, QR 사용 시 현재 정책으로 다시 계산하지 않는다.

**오류**

- 400 `COMMON-002` - 경로, 쿼리, 요청 본문의 필수값이나 형식이 올바르지 않음
- 400 `COMMON-003` - JSON 해석, 타입 변환에 실패하거나 필수 쿼리 파라미터가 없음
- 401 `AUTH-005` - accessToken 쿠키가 없거나 만료, 검증에 실패함
- 403 `AUTH-006` - 인증된 계정의 역할에 이 API 호출 권한이 없음
- 403 `COUPON-012` - 스캔한 쿠폰을 해당 점주의 매장에서 사용할 수 없음
- 403 `COUPON-013` - 스캔한 쿠폰을 해당 위치에서 사용할 수 없음
- 403 `COUPON-017` - 쿠폰 소유 회원의 현재 상태가 ACTIVE가 아님
- 409 `COUPON-014` - QR 토큰이 없거나 만료, 교체되어 유효하지 않음. 사용 완료된 QR의 재요청은 제외
- 409 `COUPON-016` - 사용 완료된 QR로 다시 요청함
- 409 `COUPON-002` - 쿠폰이 삭제됐거나 사용 가능 시간이 아님

### 매장 쿠폰 사용 이력 확인

`GET /v1/owner/stores/{storeId}/coupon-usages?from=2026-09-01&to=2026-09-30`

점주가 본인 매장에서 사용된 쿠폰 목록을 확인한다.
OWNER 역할 및 관리 권한을 먼저 확인하고 권한이 없으면 매장의 존재 여부와 무관하게 `403 STORE-002`다.
조회 기간은 한국 시간으로 from 당일 00:00 이상, to 다음 날 00:00 미만이다.
조회 기간의 길이에 상한을 두지 않으며, 결과는 `pageSize`와 `pageToken`으로 나누어 반환한다.
정렬은 `useTime DESC, usageHistoryId DESC`로 고정한다. 커서에도 두 값을 사용한다.
조회 기간과 storeId를 바꾸면 pageToken을 비우고 첫 페이지부터 요청한다.

**파라미터**

| 위치 | 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| 경로 | `storeId` | integer | O | 조회할 매장 ID (인증된 점주의 매장 관리 권한 검증) |
| 쿼리 | `from` | string(date) | O | 조회 시작 영업일 (`YYYY-MM-DD`, 포함) |
| 쿼리 | `to` | string(date) | O | 조회 종료 영업일 (`YYYY-MM-DD`, 포함, `from`과 같거나 이후) |
| 쿼리 | `pageSize` | integer | X | 한 번에 받을 개수. 기본값 20, 1 이상 100 이하 |
| 쿼리 | `pageToken` | string | X | 이전 응답의 `nextPageToken`. 생략하면 첫 페이지 |

**200 OK**

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "items": [
      {
        "usageHistoryId": 5001,
        "memberCouponId": 1001,
        "discountAmount": 3000,
        "useTime": "2026-09-30T12:30:00+09:00"
      }
    ],
    "nextPageToken": null
  }
}
```

**응답 필드**

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `items` | `array<object>` | 해당 매장의 쿠폰 사용 이력 목록. 해당 페이지에 이력이 없으면 빈 배열 |
| `nextPageToken` | string 또는 null | 다음 페이지 커서. 마지막 페이지면 null |
| `usageHistoryId` | integer | 쿠폰 사용 이력 ID |
| `memberCouponId` | integer | 사용된 사용자 쿠폰 ID |
| `discountAmount` | integer | 사용 시점에 확정해 저장한 할인액(원) |
| `useTime` | string(date-time) | 쿠폰 사용이 확정된 시각 |

**오류**

- 400 `COMMON-002` - 경로, 쿼리, 요청 본문의 필수값이나 형식이 올바르지 않음
- 400 `COMMON-003` - JSON 해석, 타입 변환에 실패하거나 필수 쿼리 파라미터가 없음
- 401 `AUTH-005` - accessToken 쿠키가 없거나 만료, 검증에 실패함
- 403 `AUTH-006` - 인증된 계정의 역할에 이 API 호출 권한이 없음
- 403 `STORE-002` - 해당 매장을 관리할 권한이 없음. 이 API는 OWNER 역할만 허용함
- 404 `STORE-001` - 요청한 매장이 없음
