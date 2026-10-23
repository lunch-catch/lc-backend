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
| `DELETE` | `/v1/admin/templates/{templateId}` | 템플릿 삭제 | ADMIN, SUPER_ADMIN |
| `POST` | `/v1/admin/templates/{templateId}/publish` | 템플릿 게시 | ADMIN, SUPER_ADMIN |
| `PATCH` | `/v1/admin/templates/{templateId}/active` | 템플릿 활성화 관리 | ADMIN, SUPER_ADMIN |
| `GET` | `/v1/owner/templates` | 점주 템플릿 목록 조회 | OWNER |
| `POST` | `/v1/owner/campaigns/{campaignId}/poster:prepareUpload` | 포스터 이미지 업로드 URL 발급 | OWNER |
| `POST` | `/v1/owner/campaigns/{campaignId}/poster` | 포스터 생성 | OWNER |
| `PATCH` | `/v1/owner/campaigns/{campaignId}/poster` | 포스터 텍스트와 글자 크기 수정 | OWNER |
| `GET` | `/v1/owner/campaigns/{campaignId}/poster` | 점주용 포스터 조회 | OWNER |
| `GET` | `/v1/campaigns/{campaignId}/poster` | 사용자용 포스터 조회 | MEMBER |

## 템플릿 생성

### `POST /v1/admin/templates`

관리자의 요청 문장으로 LLM 이 새 템플릿의 HTML 을 만들고, 정화와 구조 검증을 통과한 결과를 임시저장 버전(1번)으로 저장한다.
전체 템플릿 개수에는 상한이 있다(현재 100개).
요청 문구(`requestPrompt`)는 모델에 보내기 전에 서버가 검사한다. 걸리면 LLM을 부르지 않고 사유별 오류 코드(`POSTER-022`~`POSTER-025`)로 거부한다. 거부된 요청도 분당 3회 제한에 센다. 수정 API도 같다.

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

**응답** `201`. 요청 문구가 템플릿을 만들거나 고치라는 것이 아니라 질문이나 인사면 `200`이고, 템플릿을 만들지 않고 `message`만 돌려준다(아래 "대화만 있는 응답").

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
    "message": "따뜻한 주황 톤의 카드형 템플릿으로 만들었어요.",
    "removedElements": ["script", "div[onclick]"],
    "suggestedPrompts": ["행사명 글자를 조금 더 크게 해줘", "행사명 글자를 한 단계 작게 해줘"]
  }
}
```

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `templateId` | integer | 대화만 있는 응답이면 예 | 생성된 템플릿 ID |
| `name` | string | 대화만 있는 응답이면 예 | 템플릿 이름 |
| `versionNumber` | integer | 대화만 있는 응답이면 예 | 저장된 임시저장 버전 번호. 생성 직후는 항상 1 |
| `status` | string | 대화만 있는 응답이면 예 | `DRAFT`, `PUBLISHED`. 생성 직후는 항상 `DRAFT` |
| `html` | string | 대화만 있는 응답이면 예 | 검증을 거쳐 저장된 HTML |
| `message` | string | 아니오 | LLM 이 HTML 과 함께 보낸 짧은 설명. 설명이 없으면 빈 문자열. 검증하지 않은 LLM 문장이므로 HTML 이 아닌 텍스트로만 보여준다. 같은 `requestId` 의 재요청에서도 처음과 같은 값이다 |
| `removedElements` | array of string | 아니오 | 정화 과정에서 제거된 태그와 속성. 없으면 빈 배열 |
| `suggestedPrompts` | array of string | 아니오 | 퀵 리플라이 버튼으로 보여줄 수정 요청 문장. 모델이 이번 요청과 결과에 맞춰 만든 제안이며 최대 3개, 항목당 30자 이내다. 더 조정할 것이 없으면 빈 배열이다. 같은 `requestId` 의 재요청 응답에서는 제안을 저장하지 않으므로 빈 배열이고, 편집 화면에 다시 들어올 때도 복원되지 않는다. 검증하지 않은 모델 문장이라 텍스트로만 보여준다. 버튼을 누르면 그 문장을 그대로 템플릿 수정의 `requestPrompt` 로 보낸다 |

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `400` | `COMMON-002` | 이름이 비었거나 100자를 넘었거나, 요청 문장이 비었거나 500자를 넘었거나, 요청 식별자가 비었거나 64자를 넘었다 |
| `422` | `POSTER-001` | 슬롯이 빠졌거나 중복됨, 또는 `eventName`이나 `discount` 슬롯 요소 안에 자식 요소가 있음 |
| `422` | `POSTER-003` | 템플릿 생성 한도(현재 100개)에 도달했다 |
| `422` | `POSTER-022` | 요청 문구가 너무 짧다(글자나 숫자가 2자 미만) |
| `422` | `POSTER-023` | 요청 문구에 제어 문자나 보이지 않는 서식 문자(zero-width, 방향 전환 등)가 있다 |
| `422` | `POSTER-024` | 요청 문구가 프롬프트 인젝션 패턴("이전 지시 무시" 등)에 걸렸다 |
| `422` | `POSTER-025` | 요청 문구에 전화번호, 사업자번호, 주민번호, 카드번호, 이메일로 보이는 내용이 있다 |
| `429` | `POSTER-014` | 관리자 1명당 생성·수정을 합쳐 분당 3회 초과 |
| `503` | `POSTER-004` | LLM 응답이 30초를 넘었다, 또는 같은 `requestId`가 아직 처리 중이다 |

### 대화만 있는 응답

관리자가 템플릿을 만들거나 고치라는 요청이 아니라 질문, 설명 요청, 인사를 보내면, 모델이 이를 구별해 HTML 없이 자연어로만 답한다. 이때 생성 API 는 템플릿을 만들지 않고, 수정 API 는 새 버전을 저장하지 않는다. 응답은 `200`이고 `templateId`, `name`, `versionNumber`, `status`, `html`이 `null`이다. `message`가 답변이고(검증하지 않은 모델 문장이라 텍스트로만 보여준다, 최대 1000자), `removedElements`와 `suggestedPrompts`는 빈 배열이다. 템플릿 개수 한도, 버전 번호, 20개 보존 상한에 영향이 없다. 생성 API 가 `templateId: null`을 돌려주면 템플릿이 아직 없으니 다음 메시지도 생성 API 로 보낸다. 대화만 한 턴은 저장하지 않아 편집 화면에 다시 들어오면 복원되지 않고, 같은 `requestId` 재시도는 모델을 다시 부른다. 입력 검증과 분당 3회 제한은 그대로 적용된다. 의도 판단은 모델이 하므로 서버가 검증하지 못한다.

## 템플릿 수정

### `POST /v1/admin/templates/{templateId}/versions`

관리자의 요청 문장으로 LLM 이 해당 템플릿의 최신 버전을 다시 고치고, 정화·슬롯·구조 검증을 통과한 결과를 새 버전으로 저장한다.
템플릿 한 개는 버전을 최대 20개까지만 보존한다. 20개를 넘기면 가장 오래된 버전이 함께 지워진다. 버전 번호는 지워진 것과 무관하게 계속 증가하며 되돌리거나 재사용되지 않는다.
게시된 템플릿은 수정할 수 없다.

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

**응답** `201`. `data` 는 템플릿 생성과 같은 모양이다(`versionNumber` 는 새로 매겨진 번호). 수정 요청이 아니라 질문이나 인사면 `200`이고 새 버전을 저장하지 않으며 `message`만 돌려준다(템플릿 생성의 "대화만 있는 응답"과 같다).

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `400` | `COMMON-002` | 요청 문장이 비었거나 500자를 넘었거나, 요청 식별자가 비었거나 64자를 넘었다 |
| `404` | `POSTER-005` | 템플릿을 찾을 수 없다 |
| `409` | `POSTER-006` | 게시된 템플릿은 수정하거나 삭제할 수 없다 |
| `422` | `POSTER-001` | 슬롯이 빠졌거나 중복됨, 또는 `eventName`이나 `discount` 슬롯 요소 안에 자식 요소가 있음 |
| `422` | `POSTER-002` | 슬롯 구조가 이전 버전과 다르다 |
| `422` | `POSTER-022` | 요청 문구가 너무 짧다(글자나 숫자가 2자 미만) |
| `422` | `POSTER-023` | 요청 문구에 제어 문자나 보이지 않는 서식 문자(zero-width, 방향 전환 등)가 있다 |
| `422` | `POSTER-024` | 요청 문구가 프롬프트 인젝션 패턴("이전 지시 무시" 등)에 걸렸다 |
| `422` | `POSTER-025` | 요청 문구에 전화번호, 사업자번호, 주민번호, 카드번호, 이메일로 보이는 내용이 있다 |
| `429` | `POSTER-014` | 관리자 1명당 생성·수정을 합쳐 분당 3회 초과 |
| `503` | `POSTER-004` | LLM 응답이 30초를 넘었다, 또는 같은 `requestId`가 아직 처리 중이다 |

## 템플릿 삭제

### `DELETE /v1/admin/templates/{templateId}`

게시 전(`DRAFT`) 템플릿을 완전히 삭제한다. 템플릿 행과 그 버전 전부가 함께 지워지고,
템플릿 생성 가능 수가 1 늘어난다. 게시된 템플릿은 삭제할 수 없다.

**응답** `204`. 본문이 없다.

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `404` | `POSTER-005` | 템플릿을 찾을 수 없다 |
| `409` | `POSTER-006` | 게시된 템플릿은 수정하거나 삭제할 수 없다 |

## 템플릿 목록 조회

### `GET /v1/admin/templates`

전체 템플릿을 생성일 최신순으로 조회한다. 템플릿 개수에 상한(현재 100개)이 있어 페이지네이션은 제공하지 않고,
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
템플릿 수정이 버전을 20개 넘게 쌓으면 가장 오래된 버전부터 사라지므로, 목록에는 항상 최근 20개 이하만 보인다.

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
        "html": "<div>...</div>",
        "message": "버튼을 더 눈에 띄는 색으로 바꿨어요."
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
| `items[].message` | string | 아니오 | 이 버전을 만들 때 LLM 이 보낸 설명. 설명이 없던 버전은 빈 문자열. 검증하지 않은 LLM 문장이므로 텍스트로만 보여준다 |
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

## 포스터 이미지 업로드

### `POST /v1/owner/campaigns/{campaignId}/poster:prepareUpload`

포스터에 쓸 이미지를 올릴 presigned PUT URL을 발급합니다. 캠페인이 `DRAFT`이고 본인 소유일
때만 가능합니다.

**요청**

```json
{
  "contentType": "image/jpeg",
  "fileSize": 204800
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `contentType` | string | 예 | 파일 MIME 타입입니다. image/jpeg, image/png만 허용합니다 |
| `fileSize` | integer | 예 | 파일 크기(byte)입니다. 10MB(10485760)를 넘을 수 없습니다 |

**응답** `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "objectKey": "campaigns/10/poster/5b3f6c2a-9e1d-4b7a-8f0a-1c2d3e4f5a6b.jpg",
    "uploadUrl": "https://..."
  }
}
```

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `objectKey` | string | 아니오 | 업로드할 객체 키입니다. 포스터 생성 요청에 그대로 보냅니다 |
| `uploadUrl` | string | 아니오 | 이 주소로 파일을 직접 PUT 업로드합니다. 10분 안에 사용해야 합니다 |

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `400` | `COMMON-002` | `contentType`이 비었거나 `fileSize`가 비었다 |
| `403` | `CAMPAIGN-001` | 점주 상태가 ACTIVE가 아니다 |
| `403` | `CAMPAIGN-005` | 그 campaignId가 없거나 본인 소유가 아니다 |
| `409` | `CAMPAIGN-006` | 캠페인이 DRAFT 상태가 아니다 |
| `422` | `POSTER-017` | jpg, png가 아닌 파일 형식이다 |
| `422` | `POSTER-018` | 파일 크기가 10MB를 넘는다 |

## 포스터 생성

### `POST /v1/owner/campaigns/{campaignId}/poster`

`templateId`와 업로드를 마친 이미지의 `objectKey`로 포스터를 만듭니다. 캠페인에 이미
포스터가 있으면 새로 만들지 않고 덮어씁니다. `eventName`(가게명)과 `discount`·`period`
(쿠폰 조건으로 서버가 만든 문구)는 서버가 채웁니다. `period`는 집행 기간(날짜)이 아니라 쿠폰 사용 가능 시간(예: "매일 11:30 ~ 15:00")이라 집행 기간을 설정하기 전에도 포스터를 만들 수 있습니다. 포스터에는 광고 표시를 넣지
않습니다. 광고 표시(`adLabel`)가 들어 있는 옛 템플릿으로 만들어도 포스터에서는 뺍니다.

점주가 [포스터 텍스트 수정](#포스터-텍스트-수정)으로 고친 가게명 텍스트와 할인 추가 텍스트, 글자 크기는 같은 `templateId`로 다시 만들 때 유지됩니다. 다른 템플릿으로 만들면 레이아웃이 달라 모두 초기화됩니다. 가게명을 고치지 않았다면 다시 만들 때 지금의 가게 이름을 따릅니다.

**요청**

```json
{
  "templateId": 1,
  "objectKey": "campaigns/10/poster/5b3f6c2a-9e1d-4b7a-8f0a-1c2d3e4f5a6b.jpg"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `templateId` | integer | 예 | 포스터에 쓸 템플릿 ID입니다. 게시되고 활성인 템플릿만 가능합니다 |
| `objectKey` | string | 예 | prepareUpload로 받아 실제 업로드까지 마친 객체 키입니다 |

**응답** `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "posterId": 1,
    "campaignId": 10,
    "templateId": 1,
    "title": "분식집 - 가을 신메뉴",
    "html": "<div>...</div>",
    "slots": {
      "eventName": { "text": "분식집", "fontSizePx": 32 },
      "discount": { "baseText": "참치 김밥 2000원 할인", "suffix": "", "fontSizePx": 26 },
      "period": { "text": "매일 11:30 ~ 15:00" }
    }
  }
}
```

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `posterId` | integer | 아니오 | 포스터 ID입니다 |
| `campaignId` | integer | 아니오 | 이 포스터가 속한 캠페인 ID입니다 |
| `templateId` | integer | 아니오 | 포스터에 쓴 템플릿 ID입니다 |
| `title` | string | 아니오 | 목록 검색과 정렬용 제목입니다. "{가게명} - {템플릿 이름}"으로 만든 뒤 100자로 잘라 저장한 값입니다 |
| `html` | string | 아니오 | 5개 슬롯이 전부 채워진 완성 HTML입니다 |
| `slots` | object | 아니오 | 슬롯별 현재 값이다. 편집 화면이 `html`을 파싱하지 않고 입력칸을 채우는 용도다 |
| `slots.eventName.text` | string | 아니오 | 화면에 나가는 가게명 텍스트다 |
| `slots.eventName.fontSizePx` | integer | 가능 | 가게명 글자 크기(px)다. 템플릿이 px로 정하지 않았으면 `null`이다 |
| `slots.discount.baseText` | string | 아니오 | 쿠폰 조건으로 서버가 만든 할인 기본 문구다. 고칠 수 없다 |
| `slots.discount.suffix` | string | 아니오 | 기본 문구 뒤에 붙인 텍스트다. 없으면 빈 문자열이다 |
| `slots.discount.fontSizePx` | integer | 가능 | 할인 글자 크기(px)다. 템플릿이 px로 정하지 않았으면 `null`이다 |
| `slots.period.text` | string | 아니오 | 시간 문구다. 고칠 수 없다 |

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `400` | `COMMON-002` | `templateId`나 `objectKey`가 비었거나 `objectKey`가 1024자를 넘는다 |
| `403` | `CAMPAIGN-001` | 점주 상태가 ACTIVE가 아니다 |
| `403` | `CAMPAIGN-005` | 그 campaignId가 없거나 본인 소유가 아니다 |
| `404` | `POSTER-005` | 템플릿을 찾을 수 없다 |
| `409` | `CAMPAIGN-006` | 캠페인이 DRAFT 상태가 아니다 |
| `409` | `POSTER-015` | 템플릿이 게시되지 않았거나 활성화되지 않았다 |
| `422` | `POSTER-016` | 이미지가 실제로 업로드되지 않았다 |
| `422` | `POSTER-017` | 업로드된 파일이 허용되지 않는 형식이다 |
| `422` | `POSTER-018` | 업로드된 파일이 10MB를 넘는다 |
| `422` | `POSTER-019` | 다른 캠페인이나 다른 경로를 가리키는 `objectKey`다 |

## 포스터 텍스트 수정

### `PATCH /v1/owner/campaigns/{campaignId}/poster`

점주가 저장된 포스터의 가게명 텍스트와 글자 크기, 할인 문구 뒤에 붙일 텍스트와 글자 크기를 고친다. 부분 수정이라 보내지 않은 필드는 바꾸지 않는다. 시간(`period`)과 이미지는 고칠 수 없다. 캠페인이 `DRAFT`일 때만 가능하다.

할인 문구는 쿠폰 조건으로 서버가 만든 기본 문구(`baseText`)를 그대로 두고 그 뒤에 공백 하나와 `suffix`를 붙인 결과다. `suffix`를 빈 문자열로 보내면 붙인 텍스트가 지워진다. 가게명 텍스트를 원래 가게 이름으로 되돌리는 전용 기능은 없다.

수정한 텍스트는 포스터를 활성화할 때 자동 검수가 본다. 금지 표현은 이 API에서 검사하지 않는다. 할인 추가 텍스트에 `%`나 `원`이 들어가면 기본 문구와 어긋난 금액을 홍보할 수 있어 프론트가 경고한다. 서버는 막지 않는다.

**요청**

```json
{
  "eventName": { "text": "점심 특가 분식", "fontSizePx": 32 },
  "discount": { "suffix": "(1인 1개)", "fontSizePx": 26 }
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `eventName` | object | 아니오 | 보내지 않으면 가게명을 바꾸지 않는다 |
| `eventName.text` | string | 아니오 | 가게명 텍스트다. 앞뒤 공백을 자른 뒤 1~30자다 |
| `eventName.fontSizePx` | integer | 아니오 | 가게명 글자 크기(px)다. 16~48이다 |
| `discount` | object | 아니오 | 보내지 않으면 할인 문구를 바꾸지 않는다 |
| `discount.suffix` | string | 아니오 | 기본 문구 뒤에 붙일 텍스트다. 앞뒤 공백을 자른 뒤 0~20자다. 빈 문자열이면 지운다 |
| `discount.fontSizePx` | integer | 아니오 | 할인 글자 크기(px)다. 14~40이다 |

글자 수는 유니코드 코드 포인트로 센다. 줄바꿈과 탭 같은 제어 문자, 보이지 않는 서식 문자(zero-width 등), 줄과 문단 구분 문자는 쓸 수 없다.

**응답** `200`

포스터 생성 응답과 같은 모양이다. `html`과 `slots`가 수정 결과를 반영한다.

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `403` | `CAMPAIGN-001` | 점주 상태가 ACTIVE가 아니다 |
| `403` | `CAMPAIGN-005` | 그 campaignId가 없거나 본인 소유가 아니다 |
| `404` | `POSTER-020` | 이 캠페인에 아직 저장된 포스터가 없다 |
| `409` | `CAMPAIGN-006` | 캠페인이 DRAFT 상태가 아니다 |
| `422` | `POSTER-001` | 저장된 포스터의 가게명이나 할인 슬롯이 없거나 자식 요소를 갖고 있어 고칠 수 없다 |
| `422` | `POSTER-026` | 텍스트 길이가 범위를 벗어났거나 줄바꿈, 제어 문자가 있다 |
| `422` | `POSTER-027` | 글자 크기가 범위를 벗어났다 |

## 점주용 포스터 조회

### `GET /v1/owner/campaigns/{campaignId}/poster`

점주가 본인 소유 캠페인에 저장된 포스터 HTML을 돌려준다. 조회 시점에 새로 만들지 않고 저장된 그대로 돌려준다.
캠페인 상태와 무관하게 조회할 수 있다.

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
    "posterId": 1,
    "campaignId": 10,
    "templateId": 1,
    "title": "분식집 - 가을 신메뉴",
    "html": "<div>...</div>",
    "slots": {
      "eventName": { "text": "분식집", "fontSizePx": 32 },
      "discount": { "baseText": "참치 김밥 2000원 할인", "suffix": "", "fontSizePx": 26 },
      "period": { "text": "매일 11:30 ~ 15:00" }
    }
  }
}
```

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `posterId` | integer | 아니오 | 포스터 ID다 |
| `campaignId` | integer | 아니오 | 이 포스터가 속한 캠페인 ID다 |
| `templateId` | integer | 아니오 | 포스터에 쓴 템플릿 ID다 |
| `title` | string | 아니오 | "{가게명} - {템플릿 이름}"으로 만든 제목이다 |
| `html` | string | 아니오 | 저장된 포스터 HTML이다 |
| `slots` | object | 아니오 | 슬롯별 현재 값이다. 편집 화면이 `html`을 파싱하지 않고 입력칸을 채우는 용도다 |
| `slots.eventName.text` | string | 아니오 | 화면에 나가는 가게명 텍스트다 |
| `slots.eventName.fontSizePx` | integer | 가능 | 가게명 글자 크기(px)다. 템플릿이 px로 정하지 않았으면 `null`이다 |
| `slots.discount.baseText` | string | 아니오 | 쿠폰 조건으로 서버가 만든 할인 기본 문구다. 고칠 수 없다 |
| `slots.discount.suffix` | string | 아니오 | 기본 문구 뒤에 붙인 텍스트다. 없으면 빈 문자열이다 |
| `slots.discount.fontSizePx` | integer | 가능 | 할인 글자 크기(px)다. 템플릿이 px로 정하지 않았으면 `null`이다 |
| `slots.period.text` | string | 아니오 | 시간 문구다. 고칠 수 없다 |

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `403` | `CAMPAIGN-005` | 그 campaignId가 없거나 본인 소유가 아니다 |
| `404` | `POSTER-020` | 이 캠페인에 아직 저장된 포스터가 없다 |

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
| `404` | `POSTER-021` | 캠페인이 없거나, `DRAFT`나 `SCHEDULED`라 비공개거나, 포스터가 저장돼 있지 않다. 세 경우를 구분해 알려주지 않는다 |

## 오류 코드

요청 형식이 틀린 실패는 공통 코드(`COMMON-002`)로 온다. 공통 코드 목록은 `README.md` 에 있다.
여기에는 포스터(템플릿)만 아는 실패를 둔다.

| 코드 | 상태 | 메시지 |
|---|---|---|
| `POSTER-001` | `422` | 템플릿에 필요한 슬롯이 빠져 있거나 중복돼 있습니다. |
| `POSTER-002` | `422` | 수정한 템플릿의 구조가 이전 버전과 다릅니다. |
| `POSTER-003` | `422` | 템플릿 생성 한도에 도달해 더 만들 수 없습니다. |
| `POSTER-004` | `503` | 템플릿 생성이 지연되고 있습니다. 잠시 후 다시 시도해 주세요. |
| `POSTER-005` | `404` | 템플릿을 찾을 수 없습니다. |
| `POSTER-006` | `409` | 게시된 템플릿은 수정하거나 삭제할 수 없습니다. |
| `POSTER-007` | `409` | 이미 게시된 템플릿은 다시 게시할 수 없습니다. |
| `POSTER-008` | `404` | 존재하지 않는 버전 번호로 게시할 수 없습니다. |
| `POSTER-009` | `409` | 게시되지 않은 템플릿은 활성화할 수 없습니다. |
| `POSTER-010` | `422` | 템플릿 생성 결과가 잘렸습니다. 다시 시도해 주세요. |
| `POSTER-011` | `422` | 템플릿에 허용되지 않은 태그나 속성이 있습니다. |
| `POSTER-012` | `503` | 템플릿 생성 요청이 몰리고 있습니다. 잠시 후 다시 시도해 주세요. |
| `POSTER-013` | `422` | 요청한 문구로는 템플릿을 생성할 수 없습니다. 다른 표현으로 다시 시도해 주세요. |
| `POSTER-014` | `429` | 템플릿 생성 요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요. |
| `POSTER-015` | `409` | 게시되지 않았거나 활성화되지 않은 템플릿으로는 포스터를 만들 수 없습니다. |
| `POSTER-016` | `422` | 이미지가 실제로 업로드되지 않았습니다. |
| `POSTER-017` | `422` | jpg, png 파일만 업로드할 수 있습니다. |
| `POSTER-018` | `422` | 파일 크기는 10MB를 넘을 수 없습니다. |
| `POSTER-019` | `422` | 이 캠페인의 업로드 요청으로 받은 객체 키가 아닙니다. |
| `POSTER-020` | `404` | 이 캠페인에 저장된 포스터가 없습니다. |
| `POSTER-021` | `404` | 포스터를 조회할 수 없습니다. |
| `POSTER-022` | `422` | 요청 문구가 너무 짧습니다. 원하는 템플릿의 분위기나 색, 배치를 조금 더 자세히 적어 주세요. |
| `POSTER-023` | `422` | 요청 문구에 사용할 수 없는 문자가 포함되어 있습니다. |
| `POSTER-024` | `422` | 요청 문구에 템플릿 생성과 관련 없는 지시가 포함되어 있습니다. 만들고 싶은 템플릿의 모양만 적어 주세요. |
| `POSTER-025` | `422` | 요청 문구에 개인정보로 보이는 내용이 포함되어 있습니다. 전화번호, 사업자번호 같은 정보는 빼고 다시 요청해 주세요. |
| `POSTER-026` | `422` | 포스터 텍스트는 가게명 1~30자, 할인 추가 텍스트 0~20자로 쓰고 줄바꿈과 제어 문자는 쓸 수 없습니다. |
| `POSTER-027` | `422` | 글자 크기가 허용 범위를 벗어났습니다. 가게명은 16~48px, 할인은 14~40px입니다. |
