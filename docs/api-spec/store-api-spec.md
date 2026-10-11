# 가게 API 명세서

프론트엔드가 사용하는 가게 API의 계약이다. 현재 구현 범위는 점주 약관 동의이며, 이후 가게 등록·조회 API가 구현되면 이 문서에 추가한다.
공통 인증·응답 규약은 `README.md`, 서버 내부 구조와 정책 설정은 `store-api-design.md`를 참고한다.

## 목록

| 메서드 | 경로 | 기능 | 권한·허용 상태 |
|---|---|---|---|
| POST | `/v1/owner/terms-agreements` | 점주 약관 동의 | OWNER, ONBOARDING |

## 점주 약관 동의

### 인증

유효한 OWNER Access Token을 `accessToken` 쿠키로 보낸다. 현재 상태가 `ONBOARDING`인 점주만 호출할 수 있다.
브라우저 요청에는 `credentials: 'include'` 또는 `withCredentials: true`를 지정한다.

### 요청

```json
{
  "agreements": [
    {"termsType": "SERVICE", "termsVersion": "1.0", "agreed": true},
    {"termsType": "MARKETING", "termsVersion": "1.0", "agreed": false}
  ]
}
```

위 종류와 버전은 요청 형식의 예시이며 운영 정책이 아니다. 실제 약관 종류·현재 버전·필수 여부는 서버와 합의한 정책을 사용한다. 약관 본문·정책 조회 API는 현재 제공하지 않는다.

| 필드 | 타입 | 필수 | null 가능 | 제한·설명 |
|---|---|---|---|---|
| `agreements` | array | 예 | 아니요 | 1~100개. null 항목 불가 |
| `agreements[].termsType` | string | 예 | 아니요 | 공백이 아닌 최대 50자. 서버 정책의 종류와 정확히 일치 |
| `agreements[].termsVersion` | string | 예 | 아니요 | 공백이 아닌 최대 30자. 서버 정책의 현재 버전과 정확히 일치 |
| `agreements[].agreed` | boolean | 예 | 아니요 | 동의 여부. 선택 약관은 false 허용 |

필수 여부는 요청으로 전달하지 않는다. 같은 요청에 동일한 종류를 두 번 넣으면 `400`이다.

### 성공 응답

`201 Created`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {"requiredTermsCompleted": true}
}
```

| 필드 | 타입 | null 가능 | 설명 |
|---|---|---|---|
| `data.requiredTermsCompleted` | boolean | 아니요 | 현재 정책의 필수 약관 동의 완료 여부. 성공 시 true |

### 클라이언트에서 알아야 할 동작

- 기존에 제출한 내역과 이번 요청을 합쳐 현재 버전의 필수 약관에 모두 동의해야 성공한다. 과거 버전의 동의는 현재 버전의 동의를 대신하지 않는다.
- 필수 약관 미동의·누락으로 실패하면 이번 요청의 선택 약관 내역도 저장되지 않는다.
- 동일 점주의 동일 종류·버전은 동의 여부와 관계없이 다시 제출할 수 없다. 반복 요청은 `409`이며 기존 내역을 덮어쓰지 않는다.
- 필수 동의가 이미 완료됐다면 아직 제출하지 않은 선택 약관을 추가할 수 있다. 선택 동의 변경·철회 API는 현재 제공하지 않는다.
- 성공해도 점주 상태는 `ONBOARDING`을 유지한다. 튜토리얼 상태·토큰을 변경하거나 인증 쿠키를 갱신하지 않는다.

### 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | COMMON-002 | 입력 제한 위반, 지원하지 않는 종류·버전, 요청 내 중복 |
| 400 | COMMON-003 | JSON 또는 요청 본문을 해석할 수 없음 |
| 401 | AUTH-005 | 인증 누락·만료·무효 또는 점주 계정이 존재하지 않음 |
| 403 | AUTH-006 | OWNER가 아닌 역할 |
| 403 | OWNER-002 | 현재 상태가 ACTIVE·SUSPENDED·WITHDRAWN |
| 409 | STORE-001 | 동일 종류·버전의 내역이 이미 존재 |
| 422 | STORE-002 | 필수 약관 미동의 또는 현재 버전의 필수 동의 누락 |
| 503 | STORE-003 | 서버 약관 정책 미설정. Retry-After 헤더 포함 |
| 500 | COMMON-001 | 서버 처리 실패 |

오류 응답은 공통 응답 봉투를 따른다. 인증 실패 시 재발급 흐름은 `auth-api-spec.md`를 참고한다.