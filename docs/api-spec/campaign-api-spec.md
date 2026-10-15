# 캠페인 API 명세

점주가 캠페인을 등록하는 요청과 응답을 정의한다. 공통 규약은 [api-spec.md](api-spec.md)를
따른다. 서버 내부 처리와 근거는
[campaign-api-design.md](../design/campaign/campaign-api-design.md)를 참고한다.

캠페인 등록은 네 단계(쿠폰 조건, 포스터, 노출 대상, 하루 예산과 집행 기간)로 나뉜다. 이
문서는 아직 1단계만 담는다. 나머지 단계는 그 단계를 구현하는 이슈가 이 문서에 절을 더한다.

## API 목록

| 메서드 | 경로 | 기능 | 인증·상태 조건 |
|---|---|---|---|
| POST | `/v1/owner/campaigns` | 캠페인 등록 1단계(쿠폰 조건) | OWNER 인증, ACTIVE 상태 |

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
