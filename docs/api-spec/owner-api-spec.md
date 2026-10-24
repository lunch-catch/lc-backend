# 점주 API 명세

점주 회원가입·로그인·내 정보 조회·최초 튜토리얼 완료의 요청과 응답을 정의한다. 공통 규약은 [api-spec.md](api-spec.md)를 따른다. 공통 토큰 정책·재발급·로그아웃은 [auth-api-spec.md](auth-api-spec.md), 서버 내부 처리는 [owner-api-design.md](../design/owner/owner-api-design.md)를 참고한다.

## API 목록

| 메서드 | 경로 | 기능 | 인증·상태 조건 |
|---|---|---|---|
| POST | `/v1/owners` | 회원가입 | 인증 없이 호출 |
| POST | `/v1/owner/auth/tokens` | 로그인 | 인증 없이 호출, ONBOARDING·ACTIVE 계정만 성공 |
| GET | `/v1/owner/me` | 내 정보 조회 | OWNER 인증, ONBOARDING 조회 가능 |
| PATCH | `/v1/owner/me/tutorial` | 최초 튜토리얼 완료 | OWNER 인증, ACTIVE 상태 |

## 회원가입

`POST /v1/owners`

인증 없이 이메일·비밀번호로 점주 계정을 생성한다. 가입만으로 로그인되지는 않으며, 토큰 쿠키를 발급하지 않는다.

### 요청

```json
{
  "email": "owner@example.com",
  "password": "password12"
}
```

| 필드 | 타입 | 필수 | null 허용 | 조건 |
|---|---|---|---|---|
| email | string | 예 | 아니요 | 공백만 입력 불가, 이메일 형식, 최대 255자 |
| password | string | 예 | 아니요 | 공백만 입력 불가, 10~20자 |

비밀번호 확인 값은 받지 않으며 프론트에서 일치 여부를 확인한다.

### 성공 응답 `201`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "email": "owner@example.com",
    "role": "OWNER",
    "status": "ONBOARDING"
  }
}
```

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| email | string | 예 | 아니요 | 가입한 이메일 |
| role | string | 예 | 아니요 | 항상 OWNER |
| status | string | 예 | 아니요 | 가입 직후 ONBOARDING |

### 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | COMMON-002 | 필수값 누락·이메일 형식·비밀번호 길이 등 입력 검증 실패 |
| 400 | COMMON-003 | 요청 본문 누락 또는 읽을 수 없는 JSON |
| 409 | OWNER-001 | 이미 가입한 이메일 |
| 500 | COMMON-001 | DB 처리 실패 등 처리하지 못한 서버 오류 |

## 로그인

`POST /v1/owner/auth/tokens`

인증 없이 호출하며, ONBOARDING·ACTIVE 계정만 로그인할 수 있다.

### 요청

```json
{
  "email": "owner@example.com",
  "password": "password12"
}
```

필드 타입과 필수 여부·길이·형식은 위 회원가입 요청과 같다. 자동 로그인 선택 필드는 받지 않는다.

### 성공 응답 `200`

아래는 ACTIVE 점주의 예다.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "email": "owner@example.com",
    "role": "OWNER",
    "status": "ACTIVE",
    "tutorialViewed": false
  }
}
```

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| email | string | 예 | 아니요 | 점주 이메일 |
| role | string | 예 | 아니요 | 항상 OWNER |
| status | string | 예 | 아니요 | ONBOARDING 또는 ACTIVE |
| tutorialViewed | boolean | ACTIVE일 때만 | 아니요 | ONBOARDING 응답에서는 필드 자체를 생략 |

- 로그인마다 별도 세션을 발급하므로 같은 계정으로 여러 기기에서 동시에 사용할 수 있다. 기존 기기의 로그인은 유지한다.
- 같은 브라우저의 탭은 인증 쿠키를 공유한다. 새 탭은 `GET /v1/owner/me`로 로그인 상태를 복원한다.
- ONBOARDING이면 프론트에서 입점 등록 화면으로 이동한다.
- ACTIVE이며 `tutorialViewed: false`이면 최초 튜토리얼을 표시하고, 완료 시 아래 PATCH API를 호출한다.
- `tutorialViewed: true`이면 최초 튜토리얼을 다시 표시하지 않는다.
- Access Token·Refresh Token은 HttpOnly 쿠키로만 전달하며 응답 본문에 포함하지 않는다. 프론트는 토큰을 직접 읽거나 Authorization 헤더에 넣지 않는다.
- 쿠키의 Path·보관 시간·Secure·SameSite 등은 [공통 인증 명세](auth-api-spec.md)를 따른다.
- `status`는 로그인 시점의 값이며 이후 요청의 접근 권한을 보장하지 않는다. 가게 최종 등록 후 상태 반영만을 위한 토큰 재발급은 필요하지 않다.

### 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | COMMON-002 | 입력 검증 실패 |
| 400 | COMMON-003 | 요청 본문 누락 또는 읽을 수 없는 JSON |
| 401 | AUTH-001 | 계정 없음·비밀번호 불일치·정지·탈퇴 등 로그인 실패 |
| 503 | AUTH-002 | 세션 저장·커밋 또는 Redis 게시 전 확인의 DB 처리 실패 |
| 500 | COMMON-001 | 최초 계정 조회 실패 등 위 오류로 처리되지 않은 서버 오류 |

인증 실패의 세부 사유는 구분해 반환하지 않는다. 503 응답의 재시도 안내는 공통 규약을 따른다.

## 최초 튜토리얼 완료

`PATCH /v1/owner/me/tutorial`

- 유효한 OWNER Access Token 쿠키가 필요하며 DB의 현재 상태가 `ACTIVE`인 점주만 사용할 수 있다.
- 요청 본문은 없다. 로그인 응답의 `tutorialViewed`가 `false`이면 프론트에서 튜토리얼을 보여 주고, 완료한 시점에 호출한다.
- 이미 완료한 경우에도 `200`을 반환한다. 완료 후 로그인 응답에는 `tutorialViewed: true`가 반환된다.
- 점주 상태를 `ACTIVE`로 바꾸거나 토큰을 발급·갱신하지 않는다.

### 성공 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "tutorialViewed": true
  }
}
```

| 필드 | 타입 | 필수 | null 허용 | 설명 |
|---|---|---|---|---|
| `tutorialViewed` | boolean | 예 | 아니요 | 완료 여부이며 성공 시 항상 `true` |

### 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| `401` | `AUTH-005` | 토큰이 없거나 유효하지 않음, 해당 점주 계정이 없음 |
| `403` | `AUTH-006` | 인증된 역할이 OWNER가 아님 |
| `403` | `OWNER-002` | DB 현재 상태가 ACTIVE가 아님 |
| `500` | `COMMON-001` | DB 저장·커밋 실패 등 처리하지 못한 서버 오류 |

오류는 공통 응답 봉투를 따른다. `OWNER-002`의 메시지는 `현재 점주 상태에서는 요청한 기능을 사용할 수 없습니다.`이다. 저장에 실패하면 성공으로 응답하지 않으며, 클라이언트가 재시도해도 완료 상태는 중복되지 않는다.

## 내 정보 조회

`GET /v1/owner/me`

새로고침이나 재접속 시 로그인한 점주의 이메일, 상태, 튜토리얼 확인 여부와 가게 등록 재개 단계를 조회한다.
요청 본문은 없으며 `ownerAccessToken` 쿠키로 인증한다. OWNER 역할만 접근할 수 있고 ONBOARDING 점주도 조회할 수 있다.

성공 시 `200`과 공통 응답 봉투를 반환한다.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": { "email": "owner@example.com", "status": "ONBOARDING", "tutorialViewed": false, "registrationStep": "TERMS" }
}
```

| 필드 | 타입 | 필수 | null 가능 | 설명 |
|---|---|---|---|---|
| email | string | 예 | 아니오 | 로그인한 점주의 이메일 |
| status | string | 예 | 아니오 | DB의 현재 점주 상태: ONBOARDING, ACTIVE, SUSPENDED, WITHDRAWN |
| tutorialViewed | boolean | 예 | 아니오 | DB에 저장된 튜토리얼 확인 여부 |
| registrationStep | string | 예 | 예 | ONBOARDING 점주의 현재 가게 등록 재개 단계. 그 외 상태는 null |

| HTTP | 코드 | 조건 |
|---|---|---|
| 401 | AUTH-005 | 인증 쿠키 누락, 유효하지 않거나 만료·무효화된 Access Token, 점주 계정 없음 |
| 403 | AUTH-006 | 점주가 아닌 역할 |

registrationStep은 TERMS, STORE_INFO, LOCATION, HOURS, IMAGES, MENU 중 하나다. ONBOARDING 점주의 진행 상태가 아직 없으면 TERMS를 반환한다. 단계의 입력값이나 이전 단계 전체 데이터는 포함하지 않는다.

조회는 점주 상태나 튜토리얼 확인 여부를 변경하지 않는다. 토큰 재발급과 쿠키 갱신도 수행하지 않는다.
Access Token 만료 시 프론트는 기존 재발급 API를 사용한 뒤 조회를 다시 요청한다.
