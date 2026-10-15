# 캠페인 API 명세

점주가 캠페인을 등록하는 요청과 응답을 정의한다. 공통 규약은 [api-spec.md](api-spec.md)를
따른다. 서버 내부 처리와 근거는
[campaign-api-design.md](../design/campaign/campaign-api-design.md)를 참고한다.

캠페인 등록은 네 단계(쿠폰 조건, 포스터, 노출 대상, 하루 예산과 집행 기간)로 나뉜다. 이
문서는 1, 3, 4단계를 담는다. 2단계(포스터)는 그 단계를 구현하는 이슈가 이 문서에 절을
더한다.

## API 목록

| 메서드 | 경로 | 기능 | 인증·상태 조건 |
|---|---|---|---|
| POST | `/v1/owner/campaigns` | 캠페인 등록 1단계(쿠폰 조건) | OWNER 인증, ACTIVE 상태 |
| PATCH | `/v1/owner/campaigns/{campaignId}` | 캠페인 등록 3·4단계(노출 대상, 하루 예산과 집행 기간) | OWNER 인증, ACTIVE 상태, 본인 소유 캠페인 |

## 캠페인 등록(쿠폰 조건)

`POST /v1/owner/campaigns`

점주가 캠페인 등록 1단계로 쿠폰 조건을 입력한다. 그 가게에 작성 중인 캠페인(`DRAFT`
상태)이 없으면 새로 만들고, 있으면 그 캠페인의 쿠폰 조건만 덮어쓴다. 노출 대상(3단계)은
기본값으로, 하루 예산과 집행 기간(4단계)은 비워 둔 채로 저장된다.

### 요청

```json
{
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
| discountTargetType | string | 예 | 아니요 | `ALL`, `MENU` 중 하나 |
| discountType | string | 예 | 아니요 | `PERCENT`, `AMOUNT` 중 하나 |
| discountValue | integer | 예 | 아니요 | 양수. `discountType`이 `PERCENT`면 100 이하 |
| targetMenuId | integer | `discountTargetType`이 `MENU`면 예 | `discountTargetType`이 `ALL`이면 예, `MENU`면 아니요 | |
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
| 400 | COMMON-002 | 필수값 누락, `discountTargetType`/`discountType`이 허용값 밖, `usableStartTime`/`usableEndTime` 형식 불일치 등 |
| 403 | CAMPAIGN-001 | 점주 상태가 ACTIVE가 아님 |
| 422 | CAMPAIGN-002 | `discountType`이 `PERCENT`이고 `discountValue`가 100 초과 |
| 422 | CAMPAIGN-003 | `issueQuantity`가 0 이하 |
| 422 | CAMPAIGN-004 | `usableStartTime`~`usableEndTime`이 11:30~15:00 밖이거나 30분 단위가 아니거나 1시간 미만 |
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
