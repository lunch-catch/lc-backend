# 포스터 (poster) API 명세

관리자의 템플릿 관리 API와 사용자의 포스터 조회 API의 요청, 응답, 오류를 적는다.
공통 규약(경로, 인증, 응답 봉투, 상태 코드, 식별자, 시각)은 `README.md` 를 따르고 여기서는 반복하지 않는다.
서버가 내부에서 어떻게 처리하는지와 그렇게 정한 이유는 설계서 `../design/campaign/poster-api-design.md` 에 있다.

**표 읽는 법.** 응답 필드 표의 "null" 칸이 "가능"이면 그 필드에 `null` 이 올 수 있고, 언제 오는지는 설명 칸에 적는다.
"아니오"면 항상 값이 있다. 배열은 비어 있을 수는 있어도 `null` 은 아니다.

## 목록

| 메서드 | 경로 | 하는 일 | 권한 |
|---|---|---|---|
| `POST` | `/v1/admin/templates` | 템플릿 생성 | ADMIN, SUPER_ADMIN |
| `POST` | `/v1/admin/templates/{templateId}/versions` | 템플릿 수정(새 버전 저장) | ADMIN, SUPER_ADMIN |
| `GET` | `/v1/admin/templates` | 템플릿 목록 조회 | ADMIN, SUPER_ADMIN |
| `GET` | `/v1/admin/templates/{templateId}` | 템플릿 상세 조회 | ADMIN, SUPER_ADMIN |
| `GET` | `/v1/admin/templates/{templateId}/versions` | 템플릿 버전 목록 조회 | ADMIN, SUPER_ADMIN |
| `POST` | `/v1/admin/templates/{templateId}/publish` | 템플릿 게시 | ADMIN, SUPER_ADMIN |
| `PATCH` | `/v1/admin/templates/{templateId}/active` | 템플릿 활성화 관리 | ADMIN, SUPER_ADMIN |
| `GET` | `/v1/owner/templates` | 점주 템플릿 목록 조회 | OWNER |
| `GET` | `/v1/campaigns/{campaignId}/poster` | 사용자용 포스터 조회 | MEMBER |

## 템플릿 생성

### `POST /v1/admin/templates`

관리자의 요청 문장으로 LLM 이 새 템플릿의 HTML 을 만들고, 정화와 구조 검증을 통과한 결과를 임시저장 버전(1번)으로 저장한다.
전체 템플릿은 10개로 제한된다.

**요청**

```json
{
  "name": "가을 신메뉴",
  "requestPrompt": "가을 신메뉴 느낌으로 만들어줘",
  "requestId": "5b3f6c2a-9e1d-4b7a-8f0a-1c2d3e4f5a6b"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `name` | string | 예 | 템플릿 이름. 100자 이하 |
| `requestPrompt` | string | 예 | LLM 에 전달할 요청 문장. 500자 이하 |
| `requestId` | string | 예 | 클라이언트가 생성하는 요청 식별자. 64자 이하. 같은 값으로 다시 요청하면 새로 만들지 않고 처음 응답을 그대로 돌려준다 |

**응답** `201`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "templateId": 1,
    "name": "가을 신메뉴",
    "versionNumber": 1,
    "status": "DRAFT",
    "html": "<div>...</div>",
    "removedElements": ["script", "div[onclick]"]
  }
}
```

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `templateId` | integer | 아니오 | 생성된 템플릿 ID |
| `name` | string | 아니오 | 템플릿 이름 |
| `versionNumber` | integer | 아니오 | 저장된 임시저장 버전 번호. 생성 직후는 항상 1 |
| `status` | string | 아니오 | `DRAFT`, `PUBLISHED`. 생성 직후는 항상 `DRAFT` |
| `html` | string | 아니오 | 검증을 거쳐 저장된 HTML |
| `removedElements` | array of string | 아니오 | 정화 과정에서 제거된 태그와 속성. 없으면 빈 배열 |

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `400` | `COMMON-002` | 이름이 비었거나 100자를 넘었거나, 요청 문장이 비었거나 500자를 넘었거나, 요청 식별자가 비었거나 64자를 넘었다 |
| `422` | `POSTER-001` | 슬롯이 빠졌거나 중복됨 |
| `422` | `POSTER-003` | 템플릿이 이미 10개다 |
| `429` | `POSTER-014` | 관리자 1명당 생성·수정을 합쳐 분당 3회 초과 |
| `503` | `POSTER-004` | LLM 응답이 30초를 넘었다, 또는 같은 `requestId`가 아직 처리 중이다 |

## 템플릿 수정

### `POST /v1/admin/templates/{templateId}/versions`

관리자의 요청 문장으로 LLM 이 해당 템플릿의 최신 버전을 다시 고치고, 정화·슬롯·구조 검증을 통과한 결과를 새 버전으로 저장한다.
기존 버전은 지우지 않는다. 게시된 템플릿은 수정할 수 없다.

**요청**

```json
{
  "requestPrompt": "버튼 색상을 바꿔줘",
  "requestId": "5b3f6c2a-9e1d-4b7a-8f0a-1c2d3e4f5a6b"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `requestPrompt` | string | 예 | LLM 에 전달할 수정 요청 문장. 500자 이하 |
| `requestId` | string | 예 | 클라이언트가 생성하는 요청 식별자. 64자 이하 |

**응답** `201`. `data` 는 템플릿 생성과 같은 모양이다(`versionNumber` 는 새로 매겨진 번호).

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `400` | `COMMON-002` | 요청 문장이 비었거나 500자를 넘었거나, 요청 식별자가 비었거나 64자를 넘었다 |
| `404` | `POSTER-005` | 템플릿을 찾을 수 없다 |
| `409` | `POSTER-006` | 게시된 템플릿은 수정할 수 없다 |
| `422` | `POSTER-001` | 슬롯이 빠졌거나 중복됨 |
| `422` | `POSTER-002` | 슬롯 구조가 이전 버전과 다르다 |
| `429` | `POSTER-014` | 관리자 1명당 생성·수정을 합쳐 분당 3회 초과 |
| `503` | `POSTER-004` | LLM 응답이 30초를 넘었다, 또는 같은 `requestId`가 아직 처리 중이다 |

## 템플릿 목록 조회

### `GET /v1/admin/templates`

전체 템플릿을 생성일 최신순으로 조회한다. 템플릿은 최대 10개까지만 만들 수 있어 페이지네이션은 제공하지 않고,
서버 검색과 정렬 조건도 받지 않는다.

**응답** `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": [
    {
      "templateId": 1,
      "name": "가을 신메뉴",
      "status": "DRAFT",
      "active": false,
      "createdAt": "2026-10-01T11:00:00+09:00",
      "activatedBy": null,
      "activatedAt": null,
      "lastModifiedBy": 1,
      "lastModifiedAt": "2026-10-05T09:30:00+09:00",
      "html": "<div>...</div>"
    }
  ]
}
```

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `templateId` | integer | 아니오 | 템플릿 ID |
| `name` | string | 아니오 | 템플릿 이름 |
| `status` | string | 아니오 | `DRAFT`, `PUBLISHED` |
| `active` | boolean | 아니오 | 점주에게 노출되는 활성 상태인지 |
| `createdAt` | string (시각) | 아니오 | 템플릿 생성 시각 |
| `activatedBy` | integer | 가능 | 활성 여부를 마지막으로 바꾼 관리자 ID. 바꾼 적이 없으면 `null` |
| `activatedAt` | string (시각) | 가능 | 활성 여부를 마지막으로 바꾼 시각. 바꾼 적이 없으면 `null` |
| `lastModifiedBy` | integer | 아니오 | 마지막으로 임시저장한 관리자 ID |
| `lastModifiedAt` | string (시각) | 아니오 | 마지막으로 임시저장한 시각 |
| `html` | string | 아니오 | 미리보기 HTML. `status` 가 `PUBLISHED` 면 게시된 HTML, `DRAFT` 면 최신 임시저장 버전의 HTML |

0개면 빈 배열을 돌려준다.

## 템플릿 상세 조회

### `GET /v1/admin/templates/{templateId}`

**응답** `200`. `data` 는 목록 항목과 같은 필드다(배열이 아니라 객체 하나).

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `404` | `POSTER-005` | 템플릿을 찾을 수 없다 |

## 템플릿 버전 목록 조회

### `GET /v1/admin/templates/{templateId}/versions`

템플릿의 임시저장 버전을 최신 번호 순으로 커서 페이지네이션해 조회한다.
게시한 템플릿은 임시저장 버전이 모두 삭제되어 빈 배열을 돌려준다.

**요청**

| 이름 | 위치 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| `pageToken` | 쿼리 | string | 아니오 | 다음 페이지 커서. 비우면 처음부터 |
| `pageSize` | 쿼리 | integer | 아니오 | 한 번에 받을 개수. 기본 20, 최대 100 |

**응답** `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "items": [
      {
        "versionNumber": 2,
        "requestPrompt": "버튼 색상을 바꿔줘",
        "createdAt": "2026-10-05T09:30:00+09:00",
        "html": "<div>...</div>"
      }
    ],
    "nextPageToken": "eyJ..."
  }
}
```

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `items` | array | 아니오 | 버전 목록. 버전 번호 내림차순. 0건이면 빈 배열 |
| `items[].versionNumber` | integer | 아니오 | 버전 번호 |
| `items[].requestPrompt` | string | 아니오 | 이 버전을 만들 때 입력한 요청 문장 |
| `items[].createdAt` | string (시각) | 아니오 | 이 버전이 만들어진 시각 |
| `items[].html` | string | 아니오 | 이 버전의 HTML |
| `nextPageToken` | string | 가능 | 다음 페이지 커서. 마지막 페이지면 `null` |

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `404` | `POSTER-005` | 템플릿을 찾을 수 없다 |

## 템플릿 게시

### `POST /v1/admin/templates/{templateId}/publish`

`DRAFT` 템플릿의 임시저장 버전 중 하나를 선택해 최종 콘텐츠로 확정한다.
게시하면 이 템플릿의 임시저장 버전은(게시한 버전 포함) 모두 삭제되어 되돌릴 수 없다.
이미 같은 버전으로 게시돼 있으면 재시도로 보고 다시 게시하지 않고 그 결과를 그대로 돌려준다(`200`).
다른 버전 번호로 이미 게시된 템플릿에 게시를 요청하면 거부한다(`409`).

**요청**

```json
{
  "versionNumber": 3
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `versionNumber` | integer | 예 | 게시할 임시저장 버전 번호 |

**응답** `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "templateId": 1,
    "name": "가을 신메뉴",
    "status": "PUBLISHED",
    "html": "<div>...</div>",
    "publishedAt": "2026-10-12T10:00:00+09:00"
  }
}
```

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `templateId` | integer | 아니오 | 템플릿 ID |
| `name` | string | 아니오 | 템플릿 이름 |
| `status` | string | 아니오 | 게시 성공 직후는 항상 `PUBLISHED` |
| `html` | string | 아니오 | 게시 시점에 확정된 HTML |
| `publishedAt` | string (시각) | 아니오 | 게시 시각 |

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `400` | `COMMON-002` | `versionNumber` 가 비었거나 양수가 아니다 |
| `404` | `POSTER-005` | 템플릿을 찾을 수 없다 |
| `404` | `POSTER-008` | 존재하지 않는 버전 번호다 |
| `409` | `POSTER-007` | 다른 버전 번호로 이미 게시된 템플릿에 게시를 요청했다 |

## 템플릿 활성화 관리

### `PATCH /v1/admin/templates/{templateId}/active`

게시된 템플릿의 점주 노출 여부(활성 상태)를 바꾼다. 게시되지 않은 템플릿은 활성화할 수 없다.

**요청**

```json
{
  "active": true
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `active` | boolean | 예 | 점주에게 노출할지 여부 |

**응답** `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "templateId": 1,
    "active": true,
    "activatedBy": 7,
    "activatedAt": "2026-10-12T10:05:00+09:00"
  }
}
```

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `templateId` | integer | 아니오 | 템플릿 ID |
| `active` | boolean | 아니오 | 변경된 활성 여부 |
| `activatedBy` | integer | 아니오 | 변경한 관리자 ID |
| `activatedAt` | string (시각) | 아니오 | 변경 시각 |

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `400` | `COMMON-002` | `active` 가 비었다 |
| `404` | `POSTER-005` | 템플릿을 찾을 수 없다 |
| `409` | `POSTER-009` | 게시되지 않은 템플릿이다 |

## 점주 템플릿 목록 조회

### `GET /v1/owner/templates`

점주가 포스터 생성 시 고를 수 있는 템플릿을 생성일 최신순으로 조회한다. 게시(`PUBLISHED`)되고
활성(`active`)인 템플릿만 대상이다. 서버 검색과 정렬 조건은 받지 않는다.

**응답** `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": [
    {
      "templateId": 1,
      "name": "가을 신메뉴",
      "html": "<div>...</div>"
    }
  ]
}
```

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `templateId` | integer | 아니오 | 템플릿 ID |
| `name` | string | 아니오 | 템플릿 이름 |
| `html` | string | 아니오 | 게시된 템플릿의 확정 HTML |

0개면 빈 배열을 돌려준다.

## 사용자용 포스터 조회

### `GET /v1/campaigns/{campaignId}/poster`

가게 상세 화면에 표시할 저장된 포스터 HTML을 돌려준다. 조회 시점에 새로 만들지 않고 저장된 그대로 돌려준다.
캠페인이 `ACTIVE`, `PAUSED`, `ENDED`일 때만 조회할 수 있다. `DRAFT`, `SCHEDULED`는 비공개다.
포스터를 조회할 수 있다는 것과 쿠폰을 발급받을 수 있다는 것은 다른 판단이다. 쿠폰 발급 가능 여부는 쿠폰 도메인이 따로 정한다.

**요청**

| 이름 | 위치 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| `campaignId` | 경로 | integer | 예 | 포스터를 조회할 캠페인 ID |

**응답** `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "campaignId": 1,
    "status": "ACTIVE",
    "html": "<div>...</div>"
  }
}
```

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `campaignId` | integer | 아니오 | 캠페인 ID |
| `status` | string | 아니오 | `ACTIVE`, `PAUSED`, `ENDED` 중 하나 |
| `html` | string | 아니오 | 저장된 포스터 HTML |

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `404` | `POSTER-015` | 캠페인이 없거나, `DRAFT`나 `SCHEDULED`라 비공개거나, 포스터가 저장돼 있지 않다. 세 경우를 구분해 알려주지 않는다 |

## 오류 코드

요청 형식이 틀린 실패는 공통 코드(`COMMON-002`)로 온다. 공통 코드 목록은 `README.md` 에 있다.
여기에는 포스터(템플릿)만 아는 실패를 둔다.

| 코드 | 상태 | 메시지 |
|---|---|---|
| `POSTER-001` | `422` | 템플릿에 필요한 슬롯이 빠져 있거나 중복돼 있습니다. |
| `POSTER-002` | `422` | 수정한 템플릿의 구조가 이전 버전과 다릅니다. |
| `POSTER-003` | `422` | 템플릿은 최대 10개까지 만들 수 있습니다. |
| `POSTER-004` | `503` | 템플릿 생성이 지연되고 있습니다. 잠시 후 다시 시도해 주세요. |
| `POSTER-005` | `404` | 템플릿을 찾을 수 없습니다. |
| `POSTER-006` | `409` | 게시된 템플릿은 수정할 수 없습니다. |
| `POSTER-007` | `409` | 이미 게시된 템플릿은 다시 게시할 수 없습니다. |
| `POSTER-008` | `404` | 존재하지 않는 버전 번호로 게시할 수 없습니다. |
| `POSTER-009` | `409` | 게시되지 않은 템플릿은 활성화할 수 없습니다. |
| `POSTER-010` | `422` | 템플릿 생성 결과가 잘렸습니다. 다시 시도해 주세요. |
| `POSTER-011` | `422` | 템플릿에 허용되지 않은 태그나 속성이 있습니다. |
| `POSTER-012` | `503` | 템플릿 생성 요청이 몰리고 있습니다. 잠시 후 다시 시도해 주세요. |
| `POSTER-013` | `422` | 요청한 문구로는 템플릿을 생성할 수 없습니다. 다른 표현으로 다시 시도해 주세요. |
| `POSTER-014` | `429` | 템플릿 생성 요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요. |
| `POSTER-015` | `404` | 포스터를 찾을 수 없습니다. |
