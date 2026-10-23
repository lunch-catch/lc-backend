# 캠페인 API 명세

점주가 캠페인을 등록하는 요청과 응답을 정의한다. 공통 규약은 [api-spec.md](api-spec.md)를
따른다. 서버 내부 처리와 근거는
[campaign-api-design.md](../design/campaign/campaign-api-design.md)를 참고한다.

캠페인 등록은 네 단계(쿠폰 조건, 포스터, 노출 대상, 하루 예산과 집행 기간)로 나뉘고 이 순서대로 진행한다. 포스터를 만들 때 집행 기간은 필요하지 않다(#763). 이
문서는 1, 3, 4단계와 점주의 조회를 담는다. 2단계(포스터)는
[poster-api-spec.md](poster-api-spec.md)의 "포스터 이미지 업로드"와 "포스터 생성" 절이
담는다(#376).

## API 목록

| 메서드 | 경로 | 기능 | 인증·상태 조건 |
|---|---|---|---|
| POST | `/v1/owner/campaigns` | 캠페인 등록 1단계(쿠폰 조건) | OWNER 인증, ACTIVE 상태 |
| PATCH | `/v1/owner/campaigns/{campaignId}` | 캠페인 등록 3·4단계(노출 대상, 하루 예산과 집행 기간) | OWNER 인증, ACTIVE 상태, 본인 소유 캠페인 |
| POST | `/v1/owner/campaigns/{campaignId}:activate` | 캠페인 활성화 | OWNER 인증, ACTIVE 상태, 본인 소유 캠페인 |
| POST | `/v1/owner/campaigns/{campaignId}:pause` | 캠페인 중단 | OWNER 인증, ACTIVE 상태, 본인 소유 캠페인 |
| POST | `/v1/owner/campaigns/{campaignId}:resume` | 캠페인 재개 | OWNER 인증, ACTIVE 상태, 본인 소유 캠페인 |
| POST | `/v1/owner/campaigns/{campaignId}:end` | 캠페인 종료 | OWNER 인증, ACTIVE 상태, 본인 소유 캠페인, 캠페인이 `PAUSED` |
| POST | `/v1/owner/campaigns/{campaignId}:cancel` | 캠페인 취소(집행 전 `DRAFT`로 되돌리기) | OWNER 인증, ACTIVE 상태, 본인 소유 캠페인 |
| GET | `/v1/owner/campaigns` | 캠페인 목록 조회 | OWNER 인증 |
| GET | `/v1/owner/campaigns/{campaignId}` | 캠페인 상세 조회 | OWNER 인증, 본인 소유 캠페인 |
| GET | `/v1/admin/campaigns` | 전체 캠페인 목록 조회(관리자) | ADMIN, SUPER_ADMIN |
| GET | `/v1/admin/campaigns/{campaignId}` | 캠페인 상세 조회(관리자) | ADMIN, SUPER_ADMIN |

## 캠페인 등록(쿠폰 조건)

`POST /v1/owner/campaigns`

점주가 캠페인 등록 1단계로 쿠폰 조건을 입력한다. 그 가게에 작성 중인 캠페인(`DRAFT`
상태)이 없으면 새로 만들고, 있으면 그 캠페인의 쿠폰 조건만 덮어쓴다. 노출 대상(3단계)은
기본값으로, 하루 예산과 집행 기간(4단계)은 비워 둔 채로 저장된다. 점주는 `ENDED`가 아닌
캠페인을 동시에 하나만 가질 수 있어, 새로 만드는 경우(그 가게에 작성 중인 캠페인이 없는
경우)에는 다른 캠페인이 `DRAFT`·`SCHEDULED`·`ACTIVE`·`PAUSED` 중 하나로 남아 있으면
거부한다.

### 요청

```json
{
  "storeId": 20,
  "discountTargetType": "MENU",
  "discountType": "PERCENT",
  "discountValue": 15,
  "targetMenuId": 42,
  "issueQuantity": 100,
  "usableStartTime": "11:30",
  "usableEndTime": "13:00",
  "minOrderAmount": 8000,
  "notice": "1인 1매, 포장만 가능"
}
```

| 필드 | 타입 | 필수 | null 허용 | 조건 |
|---|---|---|---|---|
| storeId | integer | 예 | 아니요 | 로그인한 점주의 가게 ID. 점주 본인의 최종 등록된 가게여야 한다 |
| discountTargetType | string | 예 | 아니요 | `ALL`, `MENU` 중 하나 |
| discountType | string | 예 | 아니요 | `PERCENT`, `AMOUNT` 중 하나. `discountTargetType`이 `ALL`이면 `AMOUNT`만 허용 |
| discountValue | integer | 예 | 아니요 | 양수. `discountType`이 `PERCENT`면 100 이하 |
| targetMenuId | integer | `discountTargetType`이 `MENU`면 예 | `discountTargetType`이 `ALL`이면 예, `MENU`면 아니요 | 그 가게의 메뉴이고 검증된 이미지가 있어야 한다 |
| issueQuantity | integer | 예 | 아니요 | 1 이상 |
| usableStartTime | string | 예 | 아니요 | `HH:mm`, 11:30~15:00 안, 30분 단위 |
| usableEndTime | string | 예 | 아니요 | `HH:mm`, 11:30~15:00 안, 30분 단위, `usableStartTime`보다 최소 1시간 뒤 |
| minOrderAmount | integer | 아니요 | 예 | 입력하면 양수 |
| notice | string | 아니요 | 예 | 500자 이하 |

### 성공 응답

그 가게에 작성 중인 캠페인이 없어 새로 만들면 `201`, 있어서 쿠폰 조건을 덮어쓰면
`200`이다. 응답 본문 모양은 둘이 같다.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "campaignId": 501,
    "status": "DRAFT"
  }
}
```

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| campaignId | integer | 예 | 아니요 | 새로 만들었거나 덮어쓴 캠페인 ID |
| status | string | 예 | 아니요 | 항상 `DRAFT` |

### 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | COMMON-002 | 필수값 누락(`storeId`, `discountTargetType`이 `MENU`일 때의 `targetMenuId` 포함), `discountTargetType`/`discountType`이 허용값 밖, `usableStartTime`/`usableEndTime` 형식 불일치 등 |
| 403 | CAMPAIGN-001 | 점주 상태가 ACTIVE가 아님 |
| 403 | CAMPAIGN-013 | `storeId`를 보냈지만 그 가게가 존재하지 않거나 점주 본인의 최종 등록된 가게가 아님(존재 여부를 알려주지 않으려고 같은 응답) |
| 422 | CAMPAIGN-002 | `discountType`이 `PERCENT`이고 `discountValue`가 100 초과, 또는 `discountTargetType`이 `ALL`인데 `discountType`이 `PERCENT` |
| 422 | CAMPAIGN-003 | `issueQuantity`가 0 이하 |
| 422 | CAMPAIGN-004 | `usableStartTime`~`usableEndTime`이 11:30~15:00 밖이거나 30분 단위가 아니거나 1시간 미만 |
| 422 | CAMPAIGN-014 | `discountTargetType`이 `MENU`이고 `targetMenuId`를 보냈지만 그 ID의 메뉴가 없거나 그 가게의 메뉴가 아니거나 검증된 이미지가 없음 |
| 409 | CAMPAIGN-010 | 그 가게에 작성 중인 캠페인이 없는데(새로 만드는 경우), 이 점주의 다른 캠페인이 이미 `ENDED`가 아닌 상태로 남아 있음 |
| 500 | COMMON-001 | 처리하지 못한 서버 오류 |

## 캠페인 등록(노출 대상, 하루 예산과 집행 기간)

`PATCH /v1/owner/campaigns/{campaignId}`

점주가 캠페인 등록 3단계(노출 대상)와 4단계(하루 예산과 집행 기간)를 설정한다. 한
요청으로 두 단계를 같이 보내도 되고 따로 보내도 된다. 보낸 필드만 검사하고 바꾼다.

- **노출 대상 세 필드(`targetRadius`, `targetGender`, `targetAgeGroups`)는 같이 보낸다.**
  셋 중 하나라도 있으면 셋 다 필수다
- **하루 예산·집행 기간 세 필드(`dailyBudget`, `startDate`, `endDate`)도 캠페인이
  `DRAFT`일 때는 같이 보낸다.** 셋 중 하나라도 있으면 셋 다 필수다
- **캠페인이 `ACTIVE`일 때는 `dailyBudget`만 받는다.** `startDate`나 `endDate`가 같이
  오면(값이 기존과 같아도) 거부한다. 집행 기간은 활성화 이후 바꿀 수 없다
- 노출 대상은 `DRAFT` 상태에서만 바꿀 수 있다
- `ACTIVE` 상태에서 바꾼 `dailyBudget`은 그날 서빙에 바로 반영되지 않는다. 다음 영업일
  00:00부터 적용된다(근거는 설계서)

### 요청

```json
{
  "targetRadius": 1000,
  "targetGender": "ALL",
  "targetAgeGroups": ["AGE_20S", "AGE_30S"],
  "dailyBudget": 10000,
  "startDate": "2026-11-01",
  "endDate": "2026-11-30"
}
```

| 필드 | 타입 | 필수 | null 허용 | 조건 |
|---|---|---|---|---|
| targetRadius | integer | 아니요(보내면 아래 둘도 필수) | 아니요 | `500`, `1000`, `2000`, `3000` 중 하나 |
| targetGender | string | 아니요(보내면 위아래도 필수) | 아니요 | `ALL`, `MALE`, `FEMALE` 중 하나 |
| targetAgeGroups | array\<string\> | 아니요(보내면 위 둘도 필수) | 아니요 | `AGE_20S`/`AGE_30S`/`AGE_40S`/`AGE_50_PLUS` 중 1개 이상, 중복 없이 |
| dailyBudget | integer | 아니요(보내면 `DRAFT`에서는 아래 둘도 필수) | 아니요 | 양수. 최소 하루 예산(운영 설정값) 이상 |
| startDate | string(날짜) | 아니요(`DRAFT`에서 `dailyBudget`/`endDate`와 같이 보내면 필수, `ACTIVE`에서는 보내지 않음) | 아니요 | 오늘 다음 날 이후 |
| endDate | string(날짜) | 아니요(`DRAFT`에서 `dailyBudget`/`startDate`와 같이 보내면 필수, `ACTIVE`에서는 보내지 않음) | 아니요 | `startDate` 이상, `startDate`와 최소 1일 차이 |

### 성공 응답

`200`. 그 캠페인의 전체 모양 중 이 단계가 다루는 필드만 돌려준다.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "campaignId": 501,
    "targetRadius": 1000,
    "targetGender": "ALL",
    "targetAgeGroups": ["AGE_20S", "AGE_30S"],
    "dailyBudget": 10000,
    "startDate": "2026-11-01",
    "endDate": "2026-11-30"
  }
}
```

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| campaignId | integer | 예 | 아니요 | 수정한 캠페인 ID |
| targetRadius | integer | 예 | 아니요 | 수정 뒤의 현재 값 |
| targetGender | string | 예 | 아니요 | 수정 뒤의 현재 값 |
| targetAgeGroups | array\<string\> | 예 | 아니요 | 수정 뒤의 현재 값 |
| dailyBudget | integer | 예 | 예 | 지금 적용 중인 값. `ACTIVE`에서 바꿨다면 다음 영업일부터 이 값이 적용되고, 그 전까지 서빙은 바뀌기 전 값을 쓴다. `DRAFT`에서 아직 한 번도 설정 안 했으면 `null` |
| startDate | string(날짜) | 예 | 예 | 집행 시작일. `DRAFT`에서 아직 한 번도 설정 안 했으면 `null` |
| endDate | string(날짜) | 예 | 예 | 집행 종료일. `DRAFT`에서 아직 한 번도 설정 안 했으면 `null` |

### 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | COMMON-002 | 필수값 누락(위 "같이 보낸다" 조건 위반 포함), 허용값 밖, 형식 불일치 |
| 403 | CAMPAIGN-001 | 점주 상태가 ACTIVE가 아님 |
| 403 | CAMPAIGN-005 | 그 campaignId가 없거나 본인 소유가 아님(존재 여부를 알려주지 않으려고 같은 코드로 묶는다, `api-spec.md`) |
| 409 | CAMPAIGN-006 | 캠페인 상태가 그 수정을 허용하지 않음(`DRAFT`가 아닌데 노출 대상 수정, `ACTIVE`인데 집행 기간 포함) |
| 422 | CAMPAIGN-007 | `dailyBudget`이 최소 하루 예산 미만 |
| 422 | CAMPAIGN-008 | 집행 기간이 1일 미만이거나 `endDate`가 `startDate`보다 이전 |
| 422 | CAMPAIGN-009 | `startDate`가 오늘 다음 날 이전 |
| 500 | COMMON-001 | 처리하지 못한 서버 오류 |

## 캠페인 활성화

`POST /v1/owner/campaigns/{campaignId}:activate`

점주가 `DRAFT` 상태 캠페인의 활성화를 요청한다. 포스터가 있는지 확인한 뒤 자동 검수를
거친다. 검수를 통과하면 `SCHEDULED`로 바뀌고, 집행 시작일 00:00에 시스템이 `ACTIVE`로
전환한다. 검수에 실패하면 캠페인은 `DRAFT`로 남고 실패 사유를 응답에 담는다.

### 요청

본문이 없다.

### 성공 응답

`200`. 검수 결과에 따라 `status`가 다르다.

검수를 통과하면:

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "campaignId": 501,
    "status": "SCHEDULED",
    "reasons": []
  }
}
```

검수에 실패하면:

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "campaignId": 501,
    "status": "DRAFT",
    "reasons": ["FORBIDDEN_EXPRESSION", "RESOLUTION_TOO_LOW"]
  }
}
```

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| campaignId | integer | 예 | 아니요 | 활성화를 요청한 캠페인 ID |
| status | string | 예 | 아니요 | 처리 뒤의 캠페인 상태. 검수를 통과하면 `SCHEDULED`, 실패하면 `DRAFT` |
| reasons | array\<string\> | 예 | 아니요 | 검수 실패 사유 코드 목록. 통과했으면 빈 배열. 값은 서버 enum 코드 원문이다(아래 표) |

| reasons 값 | 뜻 |
|---|---|
| FORBIDDEN_EXPRESSION | 금칙 표현이 포함됨 |
| IMAGE_UNREADABLE | 이미지를 읽을 수 없음 |
| RESOLUTION_TOO_LOW | 이미지 해상도가 최소값 미만 |
| TEXT_RATIO_EXCEEDED | 이미지 속 글자 비율이 최대값 초과 |
| MODERATION_TOOL_FAILURE | 검수 도구 장애 |

**화면 문구는 이 표를 보고 프론트가 붙인다.** 서버는 사유 코드를 번역하지 않는다.

### 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 403 | CAMPAIGN-001 | 점주 상태가 ACTIVE가 아님 |
| 403 | CAMPAIGN-005 | 그 campaignId가 없거나 본인 소유가 아님(존재 여부를 알려주지 않으려고 같은 코드로 묶는다, `api-spec.md`) |
| 404 | POSTER-020 | 이 캠페인에 저장된 포스터가 없음(`poster-api-spec.md`) |
| 409 | CAMPAIGN-006 | 캠페인 상태가 `DRAFT`가 아님. 또는 검수가 끝난 뒤 포스터가 바뀌어 그 검수 결과로 전이할 수 없음(이 경우 다시 활성화를 요청하면 새 포스터로 다시 검수한다) |
| 429 | CAMPAIGN-011 | 점주 1명당 분당 3회를 넘는 활성화 요청(자체 리뷰로 찾음, `#561`) |
| 500 | COMMON-001 | 처리하지 못한 서버 오류 |

## 캠페인 중단

`POST /v1/owner/campaigns/{campaignId}:pause`

점주가 `ACTIVE` 상태의 캠페인을 직접 중단한다. 이미 점주가 중단해 `PAUSED` 상태면 다시
요청해도 성공한다(멱등한 재시도).

### 요청

본문이 없다.

### 성공 응답

`200`.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "campaignId": 501,
    "status": "PAUSED"
  }
}
```

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| campaignId | integer | 예 | 아니요 | 중단한 캠페인 ID |
| status | string | 예 | 아니요 | 처리 뒤의 캠페인 상태. 항상 `PAUSED` |

### 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 403 | CAMPAIGN-001 | 점주 상태가 ACTIVE가 아님 |
| 403 | CAMPAIGN-005 | 그 campaignId가 없거나 본인 소유가 아님(존재 여부를 알려주지 않으려고 같은 코드로 묶는다, `api-spec.md`) |
| 409 | CAMPAIGN-006 | 캠페인 상태가 `ACTIVE`가 아님. 점주 중단으로 이미 `PAUSED`인 경우는 멱등하게 `200`이다 |
| 500 | COMMON-001 | 처리하지 못한 서버 오류 |

## 캠페인 종료

`POST /v1/owner/campaigns/{campaignId}:end`

점주가 `PAUSED` 상태의 캠페인을 직접 종료한다. 진행 중(`ACTIVE`)인 캠페인은 바로 종료할
수 없고, 먼저 `:pause`로 중단한 뒤에만 종료할 수 있다(`ACTIVE` -> `PAUSED` -> `ENDED`).
종료는 되돌릴 수 없다. 종료한 캠페인은 재개할 수 없고, 점주는 `ENDED`가 아닌 캠페인이
없어지므로 새 캠페인을 등록할 수 있다(`CAMPAIGN-010`에 걸리지 않는다). 중단과 재개와 달리
이미 `ENDED`인 캠페인을 다시 종료하는 요청은 성공으로 받지 않고 거부한다.

### 요청

본문이 없다.

### 성공 응답

`200`.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "campaignId": 501,
    "status": "ENDED"
  }
}
```

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| campaignId | integer | 예 | 아니요 | 종료한 캠페인 ID |
| status | string | 예 | 아니요 | 처리 뒤의 캠페인 상태. 항상 `ENDED` |

### 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 403 | CAMPAIGN-001 | 점주 상태가 ACTIVE가 아님 |
| 403 | CAMPAIGN-005 | 그 campaignId가 없거나 본인 소유가 아님(존재 여부를 알려주지 않으려고 같은 코드로 묶는다, `api-spec.md`) |
| 409 | CAMPAIGN-006 | 캠페인 상태가 `PAUSED`가 아님(`DRAFT`, `SCHEDULED`, `ACTIVE`, 이미 `ENDED`) |
| 500 | COMMON-001 | 처리하지 못한 서버 오류 |

## 캠페인 재개

`POST /v1/owner/campaigns/{campaignId}:resume`

점주가 `PAUSED` 상태의 캠페인을 직접 재개한다. 이미 `ACTIVE` 상태면 다시 요청해도
성공한다(멱등한 재시도). 정산이 포인트 부족으로 중단한 캠페인(`PAUSED`, 사유
`NO_POINTS`)도 점주가 직접 재개할 수 있다.

### 요청

본문이 없다.

### 성공 응답

`200`.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "campaignId": 501,
    "status": "ACTIVE"
  }
}
```

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| campaignId | integer | 예 | 아니요 | 재개한 캠페인 ID |
| status | string | 예 | 아니요 | 처리 뒤의 캠페인 상태. 항상 `ACTIVE` |

### 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 403 | CAMPAIGN-001 | 점주 상태가 ACTIVE가 아님 |
| 403 | CAMPAIGN-005 | 그 campaignId가 없거나 본인 소유가 아님(존재 여부를 알려주지 않으려고 같은 코드로 묶는다, `api-spec.md`) |
| 409 | CAMPAIGN-006 | 캠페인 상태가 `PAUSED`가 아님. 이미 `ACTIVE`인 경우는 멱등하게 `200`이다 |
| 500 | COMMON-001 | 처리하지 못한 서버 오류 |

## 캠페인 취소

`POST /v1/owner/campaigns/{campaignId}:cancel`

점주가 집행 전인 `SCHEDULED` 캠페인을 `DRAFT`로 되돌린다. 되돌린 뒤에는 `DRAFT`의 기존 규칙대로
포스터, 쿠폰 조건, 하루 예산을 다시 고칠 수 있다. 고친 캠페인은 [캠페인 활성화](#캠페인-활성화)를
다시 요청하면 자동 검수를 다시 거쳐 `SCHEDULED`가 된다. 이미 `DRAFT`면 다시 요청해도 성공한다(멱등한
재시도).

집행이 시작된 캠페인(`ACTIVE`, `PAUSED`)과 끝난 캠페인(`ENDED`)은 취소할 수 없다. 00:00 배치가
`SCHEDULED`를 `ACTIVE`로 바꾸는 것과 취소가 거의 동시에 오면 먼저 처리된 쪽이 이긴다. 배치가 먼저면
취소는 `409 CAMPAIGN-006`이다.

### 요청

본문이 없다.

### 성공 응답

`200`.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "campaignId": 501,
    "status": "DRAFT"
  }
}
```

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| campaignId | integer | 예 | 아니요 | 취소한 캠페인 ID |
| status | string | 예 | 아니요 | 처리 뒤의 캠페인 상태. 항상 `DRAFT` |

### 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 403 | CAMPAIGN-001 | 점주 상태가 ACTIVE가 아님 |
| 403 | CAMPAIGN-005 | 그 campaignId가 없거나 본인 소유가 아님(존재 여부를 알려주지 않으려고 같은 코드로 묶는다, `api-spec.md`) |
| 409 | CAMPAIGN-006 | 캠페인 상태가 `SCHEDULED`가 아님(`ACTIVE`, `PAUSED`, `ENDED`). 이미 `DRAFT`인 경우는 멱등하게 `200`이다 |
| 500 | COMMON-001 | 처리하지 못한 서버 오류 |

## 캠페인 목록 조회

`GET /v1/owner/campaigns`

점주가 본인 소유 캠페인을 최근 생성 순으로 조회한다. 커서 페이지네이션은 공통 규약
(`api-spec.md`)을 따른다.

### 요청

| 파라미터 | 위치 | 타입 | 필수 | 조건 |
|---|---|---|---|---|
| pageSize | 쿼리 | integer | 아니요 | 한 번에 받을 개수. 기본 20, 최대 100 |
| pageToken | 쿼리 | string | 아니요 | 다음 페이지 커서. 비우면 처음부터 |

### 성공 응답

`200`.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "items": [
      {
        "campaignId": 501,
        "storeId": 20,
        "status": "ACTIVE",
        "discountTargetType": "MENU",
        "discountType": "PERCENT",
        "discountValue": 15,
        "dailyBudget": 10000,
        "startDate": "2026-11-01",
        "endDate": "2026-11-30",
        "createdAt": "2026-10-20T11:00:00+09:00"
      }
    ],
    "nextPageToken": null
  }
}
```

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| items[].campaignId | integer | 예 | 아니요 | 캠페인 ID |
| items[].storeId | integer | 예 | 아니요 | 그 캠페인이 속한 가게 ID |
| items[].status | string | 예 | 아니요 | 캠페인 상태 |
| items[].discountTargetType | string | 예 | 아니요 | 할인 대상 |
| items[].discountType | string | 예 | 아니요 | 할인 방식 |
| items[].discountValue | integer | 예 | 아니요 | 할인값 |
| items[].dailyBudget | integer | 예 | 예 | 하루 예산. 아직 한 번도 설정 안 했으면 `null` |
| items[].startDate | string(날짜) | 예 | 예 | 집행 시작일. 아직 한 번도 설정 안 했으면 `null` |
| items[].endDate | string(날짜) | 예 | 예 | 집행 종료일. 아직 한 번도 설정 안 했으면 `null` |
| items[].createdAt | string(시각) | 예 | 아니요 | 캠페인을 만든 시각 |
| nextPageToken | string | 예 | 예 | 다음 페이지 커서. 마지막 페이지면 `null` |

### 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 500 | COMMON-001 | 처리하지 못한 서버 오류 |

## 캠페인 상세 조회

`GET /v1/owner/campaigns/{campaignId}`

점주가 본인 소유 캠페인 1건의 쿠폰 조건, 노출 대상, 하루 예산과 집행 기간, 상태를
조회한다.

### 성공 응답

`200`.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "campaignId": 501,
    "storeId": 20,
    "status": "ACTIVE",
    "pausedReason": null,
    "discountTargetType": "MENU",
    "discountType": "PERCENT",
    "discountValue": 15,
    "targetMenuId": 42,
    "issueQuantity": 100,
    "usableStartTime": "11:30",
    "usableEndTime": "13:00",
    "minOrderAmount": 8000,
    "notice": "1인 1매, 포장만 가능",
    "targetRadius": 1000,
    "targetGender": "ALL",
    "targetAgeGroups": ["AGE_20S", "AGE_30S"],
    "dailyBudget": 10000,
    "startDate": "2026-11-01",
    "endDate": "2026-11-30",
    "statusChangedAt": "2026-11-01T00:00:00+09:00",
    "createdAt": "2026-10-20T11:00:00+09:00"
  }
}
```

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| campaignId | integer | 예 | 아니요 | 캠페인 ID |
| storeId | integer | 예 | 아니요 | 그 캠페인이 속한 가게 ID |
| status | string | 예 | 아니요 | 캠페인 상태 |
| pausedReason | string | 예 | 예 | `PAUSED`일 때 중단 사유. 그 외 상태면 `null` |
| discountTargetType | string | 예 | 아니요 | 할인 대상 |
| discountType | string | 예 | 아니요 | 할인 방식 |
| discountValue | integer | 예 | 아니요 | 할인값 |
| targetMenuId | integer | 예 | 예 | 할인 대상 메뉴 ID. `discountTargetType`이 `ALL`이면 `null` |
| issueQuantity | integer | 예 | 아니요 | 발급 수량 |
| usableStartTime | string | 예 | 아니요 | 쿠폰 이용 가능 시작 시각 |
| usableEndTime | string | 예 | 아니요 | 쿠폰 이용 가능 종료 시각 |
| minOrderAmount | integer | 예 | 예 | 최소 주문 금액. 입력 안 했으면 `null` |
| notice | string | 예 | 예 | 안내 문구. 입력 안 했으면 `null` |
| targetRadius | integer | 예 | 아니요 | 노출 반경(m) |
| targetGender | string | 예 | 아니요 | 노출 대상 성별 |
| targetAgeGroups | array\<string\> | 예 | 아니요 | 노출 대상 연령대 |
| dailyBudget | integer | 예 | 예 | 지금 적용 중인 하루 예산. 아직 한 번도 설정 안 했으면 `null` |
| startDate | string(날짜) | 예 | 예 | 집행 시작일. 아직 한 번도 설정 안 했으면 `null` |
| endDate | string(날짜) | 예 | 예 | 집행 종료일. 아직 한 번도 설정 안 했으면 `null` |
| statusChangedAt | string(시각) | 예 | 아니요 | 상태가 마지막으로 바뀐 시각 |
| createdAt | string(시각) | 예 | 아니요 | 캠페인을 만든 시각 |

### 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 403 | CAMPAIGN-005 | 그 campaignId가 없거나 본인 소유가 아님(존재 여부를 알려주지 않으려고 같은 코드로 묶는다, `api-spec.md`) |
| 500 | COMMON-001 | 처리하지 못한 서버 오류 |

## 전체 캠페인 목록 조회(관리자)

`GET /v1/admin/campaigns`

관리자가 모든 점주의 캠페인을 최근 생성 순으로 조회한다. 커서 페이지네이션은 공통 규약
(`api-spec.md`)을 따른다. 가게와 상태로 걸러 볼 수 있다. 상호로 찾으려면 클라이언트가
가게 ID로 바꿔 `storeId`로 보낸다.

### 요청

| 파라미터 | 위치 | 타입 | 필수 | 조건 |
|---|---|---|---|---|
| storeId | 쿼리 | integer | 아니요 | 그 가게의 캠페인만 조회. 비우면 전체 |
| status | 쿼리 | string | 아니요 | `DRAFT`, `SCHEDULED`, `ACTIVE`, `PAUSED`, `ENDED` 중 하나. 비우면 전체 |
| pageSize | 쿼리 | integer | 아니요 | 한 번에 받을 개수. 기본 20, 최대 100 |
| pageToken | 쿼리 | string | 아니요 | 다음 페이지 커서. 비우면 처음부터 |

### 성공 응답

`200`.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "items": [
      {
        "campaignId": 501,
        "ownerId": 7,
        "storeId": 20,
        "storeName": "런치캐치 식당",
        "status": "ACTIVE",
        "discountTargetType": "MENU",
        "discountType": "PERCENT",
        "discountValue": 15,
        "dailyBudget": 10000,
        "startDate": "2026-11-01",
        "endDate": "2026-11-30",
        "createdAt": "2026-10-20T11:00:00+09:00"
      }
    ],
    "nextPageToken": null
  }
}
```

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| items[].campaignId | integer | 예 | 아니요 | 캠페인 ID |
| items[].ownerId | integer | 예 | 아니요 | 캠페인을 만든 점주 ID |
| items[].storeId | integer | 예 | 아니요 | 그 캠페인이 속한 가게 ID |
| items[].storeName | string | 예 | 예 | 가게 상호. 최종 등록된 가게가 아니면 `null` |
| items[].status | string | 예 | 아니요 | 캠페인 상태 |
| items[].discountTargetType | string | 예 | 아니요 | 할인 대상 |
| items[].discountType | string | 예 | 아니요 | 할인 방식 |
| items[].discountValue | integer | 예 | 아니요 | 할인값 |
| items[].dailyBudget | integer | 예 | 예 | 하루 예산. 아직 한 번도 설정 안 했으면 `null` |
| items[].startDate | string(날짜) | 예 | 예 | 집행 시작일. 아직 한 번도 설정 안 했으면 `null` |
| items[].endDate | string(날짜) | 예 | 예 | 집행 종료일. 아직 한 번도 설정 안 했으면 `null` |
| items[].createdAt | string(시각) | 예 | 아니요 | 캠페인을 만든 시각 |
| nextPageToken | string | 예 | 예 | 다음 페이지 커서. 마지막 페이지면 `null` |

소진 포인트와 누적 유효 노출은 담지 않는다. 클라이언트가 분석의 캠페인 리포트를 따로
불러 합친다.

### 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | COMMON-002 | `status`가 허용값이 아니거나 `storeId` 형식이 올바르지 않음 |
| 500 | COMMON-001 | 처리하지 못한 서버 오류 |

## 캠페인 상세 조회(관리자)

`GET /v1/admin/campaigns/{campaignId}`

관리자가 캠페인 1건의 쿠폰 조건, 노출 대상, 하루 예산과 집행 기간, 상태, 상태 이력,
포스터를 조회한다. 소유자를 가리지 않는다.

### 성공 응답

`200`.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "campaignId": 501,
    "ownerId": 7,
    "storeId": 20,
    "storeName": "런치캐치 식당",
    "status": "ACTIVE",
    "pausedReason": null,
    "discountTargetType": "MENU",
    "discountType": "PERCENT",
    "discountValue": 15,
    "targetMenuId": 42,
    "issueQuantity": 100,
    "usableStartTime": "11:30",
    "usableEndTime": "13:00",
    "minOrderAmount": 8000,
    "notice": "1인 1매",
    "targetRadius": 1000,
    "targetGender": "ALL",
    "targetAgeGroups": ["AGE_20S", "AGE_30S"],
    "dailyBudget": 10000,
    "startDate": "2026-11-01",
    "endDate": "2026-11-30",
    "statusChangedAt": "2026-11-01T00:00:00+09:00",
    "createdAt": "2026-10-20T11:00:00+09:00",
    "statusLogs": [
      {
        "fromStatus": "SCHEDULED",
        "toStatus": "ACTIVE",
        "actorType": "SYSTEM",
        "reason": null,
        "changedAt": "2026-11-01T00:00:00+09:00"
      }
    ],
    "poster": {
      "posterId": 9,
      "templateId": 3,
      "title": "점심 특선",
      "html": "<div>...</div>"
    }
  }
}
```

점주용 상세 조회와 같은 필드(캠페인 ID부터 `createdAt`까지)에 아래 필드를 더한다.

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| ownerId | integer | 예 | 아니요 | 캠페인을 만든 점주 ID |
| storeName | string | 예 | 예 | 가게 상호. 최종 등록된 가게가 아니면 `null` |
| statusLogs | array | 예 | 아니요 | 상태 이력. 일어난 순서대로. 전이가 없으면 빈 배열 |
| statusLogs[].fromStatus | string | 예 | 아니요 | 바뀌기 전 상태 |
| statusLogs[].toStatus | string | 예 | 아니요 | 바뀐 뒤 상태 |
| statusLogs[].actorType | string | 예 | 아니요 | 상태를 바꾼 주체 |
| statusLogs[].reason | string | 예 | 예 | 상태를 바꾼 사유. 없으면 `null` |
| statusLogs[].changedAt | string(시각) | 예 | 아니요 | 상태가 바뀐 시각 |
| poster | object | 예 | 예 | 저장된 포스터. 아직 저장하지 않았으면 `null` |
| poster.posterId | integer | 예 | 아니요 | 포스터 ID |
| poster.templateId | integer | 예 | 아니요 | 포스터를 만든 템플릿 ID |
| poster.title | string | 예 | 아니요 | 포스터 제목 |
| poster.html | string | 예 | 아니요 | 저장된 포스터 HTML |

### 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 404 | CAMPAIGN-012 | 그 campaignId의 캠페인이 없음 |
| 500 | COMMON-001 | 처리하지 못한 서버 오류 |
