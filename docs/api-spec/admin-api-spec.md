# 관리자 API 명세

관리자 계정 발급과 로그인 계약을 정의한다. 토큰 재발급과 로그아웃의 계약은 [인증 명세](auth-api-spec.md#관리자)가 소유한다. 서버 처리와 저장 구조는 [관리자 설계서](../design/admin/admin-api-design.md)를 따른다.

공통 응답 규약은 [API 공통 규약](api-spec.md), 토큰 수명·쿠키 정책은 [공통 토큰 정책](auth-api-spec.md#공통-토큰-정책)을 따른다. 아래 쿠키 수명은 기본 설정이며 `JWT_COOKIE_SECURE=true`이면 `Secure` 속성이 추가된다.

## 목록

| 메서드 | 경로 | 하는 일 | 권한 |
| --- | --- | --- | --- |
| `POST` | `/v1/admin/admins` | 관리자 계정 발급 | Access Token, `SUPER_ADMIN` |
| `POST` | `/v1/admin/auth/tokens` | 관리자 로그인 | 기존 토큰 불필요 |
| `POST` | `/v1/admin/auth/tokens:refresh` | 관리자 토큰 재발급 | Refresh Token 쿠키, 기존 Access Token 불필요 |
| `DELETE` | `/v1/admin/auth/tokens` | 관리자 로그아웃 | Access Token, `ADMIN` 또는 `SUPER_ADMIN` |

## 로그인·재발급 요청 제한

인증 요청 제한 필터는 IP별 1분 고정 윈도우에서 10회를 허용한다. 대상 로그인·재발급 경로들이 같은 IP 카운터를 공유하므로 관리자 API별로 각각 10회가 아니다. 초과 시 `429`와 남은 대기 시간(초)의 `Retry-After`를 반환하며, 현재 구현은 이 응답에 JSON 본문이나 오류 코드를 넣지 않는다. 캐시 장애로 횟수를 확인하지 못하면 요청을 통과시킨다. 계정별 로그인 실패 잠금과는 별개다.

## 관리자 계정 발급

`POST /v1/admin/admins`

최고 관리자가 `ADMIN` 또는 `SUPER_ADMIN` 계정을 발급한다.

### 인증 및 권한

- 유효한 `accessToken` 쿠키가 필요하며 역할은 `SUPER_ADMIN`이어야 한다.
- 서버는 JWT 권한 외에 DB의 발급자 계정이 현재 `ACTIVE`, `SUPER_ADMIN`인지 다시 확인한다.
- Access Token 폐기 기준 조회는 필수다. 저장소 장애 시 발급을 진행하지 않고 `503 AUTH-002`를 반환한다.

### 요청

```http
POST /v1/admin/admins
Content-Type: application/json
Cookie: accessToken=<JWT>
```

```json
{
  "loginId": "admin01",
  "initialPassword": "Initial123!",
  "name": "홍길동",
  "role": "ADMIN"
}
```

| 필드 | 타입 | 필수 | null 가능 | 설명 |
| --- | --- | --- | --- | --- |
| `loginId` | string | 예 | 아니오 | 공백 불가, 50자 이하, 영문·숫자·`.`·`_`·`-`만 허용 |
| `initialPassword` | string | 예 | 아니오 | 공백 불가, 10~72자이면서 UTF-8 72바이트 이하, 영문 대문자·소문자·숫자·특수문자 모두 포함 |
| `name` | string | 예 | 아니오 | 공백 불가, 50자 이하, 유니코드 문자·결합 문자·공백·`.`·`'`·`-`만 허용 |
| `role` | string | 예 | 아니오 | `ADMIN`, `SUPER_ADMIN`만 허용 |

### 응답 `201 Created`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "loginId": "admin01",
    "name": "홍길동",
    "role": "ADMIN"
  }
}
```

| 필드 | 타입 | 필수 | null 가능 | 설명 |
| --- | --- | --- | --- | --- |
| `data.loginId` | string | 예 | 아니오 | 발급된 관리자 로그인 ID |
| `data.name` | string | 예 | 아니오 | 발급된 관리자 이름 |
| `data.role` | string | 예 | 아니오 | 발급된 관리자 권한 |

### 오류

| HTTP | 코드 | 조건 |
| --- | --- | --- |
| `400` | `COMMON-002` | 요청 필드 검증 실패 |
| `401` | `AUTH-005` | Access Token 없음·무효·만료 또는 폐기 기준에 의해 무효 |
| `403` | `AUTH-006` | 최고 관리자 권한 없음 또는 DB 재확인 시 발급자 계정 없음·비활성·권한 변경 |
| `409` | `ADMIN-001` | 로그인 ID 중복 |
| `503` | `AUTH-002` | 필수 Access Token 폐기 기준 조회 장애 |

## 관리자 로그인

`POST /v1/admin/auth/tokens`

관리자 ID와 비밀번호를 검증하고 토큰을 발급한다. 기존 Access Token은 필요하지 않다. 계정은 `ACTIVE`이며 관리자 역할이어야 한다.

### 요청

```http
POST /v1/admin/auth/tokens
Content-Type: application/json
```

```json
{
  "loginId": "admin01",
  "password": "Initial123!"
}
```

| 필드 | 타입 | 필수 | null 가능 | 설명 |
| --- | --- | --- | --- | --- |
| `loginId` | string | 예 | 아니오 | 공백 불가, 50자 이하 |
| `password` | string | 예 | 아니오 | 공백 불가. UTF-8 72바이트 초과 시 `401 AUTH-001` 반환 |

계정 발급 시의 비밀번호 조합 규칙을 로그인 요청에 다시 적용하지 않는다.

### 응답 `200 OK`

```http
Set-Cookie: accessToken=<JWT>; Path=/; Max-Age=1800; HttpOnly; SameSite=Strict
Set-Cookie: refreshToken=<opaque>; Path=/v1/admin/auth/; Max-Age=86400; HttpOnly; SameSite=Strict
```

`JWT_COOKIE_SECURE=true`인 환경에서는 위 두 쿠키에 `Secure`도 포함된다.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "adminId": 1,
    "name": "홍길동",
    "role": "ADMIN"
  }
}
```

| 필드 | 타입 | 필수 | null 가능 | 설명 |
| --- | --- | --- | --- | --- |
| `data.adminId` | number (int64) | 예 | 아니오 | 관리자 식별자 |
| `data.name` | string | 예 | 아니오 | 관리자 이름 |
| `data.role` | string | 예 | 아니오 | `ADMIN` 또는 `SUPER_ADMIN` |

### 오류

| HTTP | 코드 | 조건 |
| --- | --- | --- |
| `400` | `COMMON-002` | 필수값 누락·공백, 로그인 ID 길이 초과 등 DTO 검증 실패 |
| `401` | `AUTH-001` | 계정 없음, 비밀번호 불일치·UTF-8 72바이트 초과, 계정 사용 불가 또는 관리자 역할 아님 |
| `429` | 없음 | IP별 인증 요청 제한 초과. 본문 없이 `Retry-After` 제공 |
| `503` | `AUTH-002` | 필수 Refresh Token DB 저장·확인 실패 |

## 관리자 토큰 재발급

[인증 명세의 관리자 절](auth-api-spec.md#관리자)을 따른다. 구현 기준과 브랜치별 반영 상태는 [관리자 설계서](../design/admin/admin-api-design.md#구현-근거)를 따른다.

## 관리자 로그아웃

[인증 명세의 관리자 절](auth-api-spec.md#관리자)을 따른다.

## 오류 코드

응답 봉투는 [공통 규약](api-spec.md), 인증 오류의 코드·메시지는 [인증 명세](auth-api-spec.md)를 따른다. 관리자 오류는 아래 표를 따른다. `503 AUTH-002`에는 `Retry-After: 1`이 추가된다. 인증 요청 제한의 `429`는 본문이 없다.
| 코드 | 상태 | 메시지 |
| --- | --- | --- |
| `ADMIN-001` | 409 | 이미 사용 중인 관리자 ID입니다. |