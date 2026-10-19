# 관리자 (admin) API 명세

관리자 계정 발급·로그인·토큰 재발급·로그아웃, 그리고 운영(`ops`)이 소유한 배치 작업 수동 재실행 API를 안내한다. 계정 발급과 로그인, 배치 수동 재실행의 요청·응답·오류는 이 문서에서 정의한다. 토큰·쿠키 정책과 재발급·로그아웃의 상세 계약은 [auth-api-spec.md](./auth-api-spec.md)를 따른다. 공통 규약은 [api-spec.md](./api-spec.md)를 따른다. 관리자 계정 기능의 서버 처리와 저장 구조는 [admin-api-design.md](../design/admin/admin-api-design.md)에, 배치 수동 재실행의 서버 처리는 [batch-execution-retry-api-design.md](../design/ops/batch-execution-retry-api-design.md)에 있다.

## 목록과 구현 범위

관리자 API의 전체 문서 범위는 계정 발급·로그인·토큰 재발급·로그아웃, 점주와 사용자 관리, 입점 신청 조회, 플랫폼 설정값, 감사 로그, 배치 작업 수동 재실행을 포함한다. 아래 표는 현재 이 문서에서 안내하는 구현된 관리자 API다. 나머지 관리 기능은 전체 문서 범위에 포함되지만, 이 목록만으로 구현 완료를 의미하지는 않는다.

| 메서드 | 경로 | 기능 | 인증 및 권한 |
| --- | --- | --- | --- |
| `POST` | `/v1/admin/admins` | 관리자 계정 발급 | Access Token, `SUPER_ADMIN` |
| `POST` | `/v1/admin/auth/tokens` | 관리자 로그인 | 기존 토큰 불필요 |
| `DELETE` | `/v1/admin/auth/tokens` | 관리자 로그아웃 | Access Token, `ADMIN` 또는 `SUPER_ADMIN` |
| `POST` | `/v1/admin/auth/tokens:refresh` | 관리자 토큰 재발급 | Refresh Token. 상세 계약은 [인증 명세](./auth-api-spec.md#관리자) |
| `POST` | `/v1/admin/batch-executions/{jobName}/{businessDate}:retry` | 배치 작업 수동 재실행 | Access Token, `ADMIN` 또는 `SUPER_ADMIN` |


## 로그인·재발급 요청 제한

인증 요청 제한 필터는 IP별 1분 고정 윈도우에서 10회를 허용한다. 대상 로그인·재발급 경로들이 같은 IP 카운터를 공유하므로 관리자 API별로 각각 10회가 아니다. 초과 시 `429`와 남은 대기 시간(초)의 `Retry-After`를 반환하며, 현재 구현은 이 응답에 JSON 본문이나 오류 코드를 넣지 않는다. 캐시 장애로 횟수를 확인하지 못하면 요청을 통과시킨다. 계정별 로그인 실패 잠금과는 별개다.

## 관리자 계정 발급

### `POST /v1/admin/admins`

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

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `loginId` | string | 예 | 공백 불가, 50자 이하, 영문·숫자·`.`·`_`·`-`만 허용 |
| `initialPassword` | string | 예 | 공백 불가, 10~72자이면서 UTF-8 72바이트 이하, 영문 대문자·소문자·숫자·특수문자 모두 포함 |
| `name` | string | 예 | 공백 불가, 50자 이하, 유니코드 문자·결합 문자·공백·`.`·`'`·`-`만 허용 |
| `role` | string | 예 | `ADMIN`, `SUPER_ADMIN`만 허용 |

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

| 응답 필드 | 타입 | 뜻 |
| --- | --- | --- |
| `data.loginId` | string | 발급된 관리자 로그인 ID |
| `data.name` | string | 발급된 관리자 이름 |
| `data.role` | string | 발급된 관리자 권한 |

### 오류

| 상태 | 코드 | 조건 |
| --- | --- | --- |
| `400` | `COMMON-002` | 요청 필드 검증 실패 |
| `401` | `AUTH-005` | Access Token 없음·무효·만료 또는 폐기 기준에 의해 무효 |
| `403` | `AUTH-006` | 최고 관리자 권한 없음 또는 DB 재확인 시 발급자 계정 없음·비활성·권한 변경 |
| `409` | `ADMIN-001` | 로그인 ID 중복 |
| `503` | `AUTH-002` | 필수 Access Token 폐기 기준 조회 장애 |

## 관리자 로그인

### `POST /v1/admin/auth/tokens`

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

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `loginId` | string | 예 | 공백 불가, 50자 이하 |
| `password` | string | 예 | 공백 불가. UTF-8 72바이트 초과는 서비스에서 `401 AUTH-001`로 처리 |

로그인 DTO에는 비밀번호 72자 상한 검증이 없다. 계정 발급 시의 비밀번호 조합 규칙을 로그인 요청에 다시 적용하지 않는다.

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

| 응답 필드 | 타입 | 뜻 |
| --- | --- | --- |
| `data.adminId` | number (int64) | 관리자 식별자 |
| `data.name` | string | 관리자 이름 |
| `data.role` | string | `ADMIN` 또는 `SUPER_ADMIN` |

현재 응답에는 `expiresInSeconds`, `admin` 중첩 객체, `loginId`가 없다.

### 오류

| 상태 | 코드 | 조건 |
| --- | --- | --- |
| `400` | `COMMON-002` | 필수값 누락·공백, 로그인 ID 길이 초과 등 DTO 검증 실패 |
| `401` | `AUTH-001` | 계정 없음, 비밀번호 불일치·UTF-8 72바이트 초과, 계정 사용 불가 또는 관리자 역할 아님 |
| `429` | 없음 | IP별 인증 요청 제한 초과. 본문 없이 `Retry-After` 제공 |
| `503` | `AUTH-002` | 필수 Refresh Token DB 저장·확인 실패 |

## 관리자 토큰 재발급

[인증 명세의 관리자 절](./auth-api-spec.md#관리자)을 따른다.

## 관리자 로그아웃

[인증 명세의 관리자 절](./auth-api-spec.md#관리자)을 따른다.

## 관련 인증 계약

두 토큰은 응답 본문이 아닌 HttpOnly 쿠키로 전달한다. 로그인 응답과 재발급 응답의 `data`는 동일하다. 쿠키 경로·기본 수명·Secure 설정과 로그인·재발급 요청 제한, `AUTH-*` 오류 메시지는 [인증 명세](./auth-api-spec.md)에 있다. 새 로그인은 기존 Refresh Token을 대체하지만 기존 Access Token을 모두 폐기하지는 않는다.

## 배치 작업 수동 재실행

### `POST /v1/admin/batch-executions/{jobName}/{businessDate}:retry`

`FAILED`로 확정된 00:00 묶음(`DAILY_0000`) 또는 등록된 묶음 밖 작업(`StandaloneJob`)을 관리자가 다시 실행한다. 개별 단계 이름처럼 그 둘이 아닌 `jobName`은 받지 않는다. 그런 이름은 묶음 드라이버 없이 독자적으로 다시 실행할 경로가 없기 때문이다(상세 근거는 [batch-execution-retry-api-design.md](../design/ops/batch-execution-retry-api-design.md)).

접수만 하고 바로 `202`를 돌려준다. 실제 재실행은 접수 뒤 별도로 돈다. 처리한 관리자는 감사 로그에 남는다.

### 인증 및 권한

- 유효한 `accessToken` 쿠키가 필요하며 역할은 `ADMIN` 또는 `SUPER_ADMIN`이어야 한다.

### 요청

```http
POST /v1/admin/batch-executions/DAILY_0000/2026-09-30:retry
Cookie: accessToken=<JWT>
```

| 경로 변수 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `jobName` | string | 예 | `batch_execution_log.job_name`. 50자 이하 |
| `businessDate` | string (date) | 예 | 재실행할 영업일. `YYYY-MM-DD` |

본문은 없다.

### 응답 `202 Accepted`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "runId": 301,
    "jobName": "DAILY_0000",
    "businessDate": "2026-09-30",
    "status": "RUNNING"
  }
}
```

| 응답 필드 | 타입 | 뜻 |
| --- | --- | --- |
| `data.runId` | number (int64) | 재실행한 실행 기록 ID |
| `data.jobName` | string | 재실행한 작업명 |
| `data.businessDate` | string (date) | 재실행한 영업일 |
| `data.status` | string | 접수 시점의 상태. 항상 `RUNNING` |

실제로 성공했는지 실패했는지는 이 응답에 없다. 감사 로그와 배치 실행 기록으로 확인한다. 운영 전역의 배치 실행 기록을 조회하는 API는 아직 없다.

### 오류

| 상태 | 코드 | 조건 |
| --- | --- | --- |
| `401` | `AUTH-005` | Access Token 없음·무효·만료 또는 폐기 기준에 의해 무효 |
| `403` | `AUTH-006` | 관리자 권한 없음 |
| `404` | `OPS-002` | 그 (`jobName`, `businessDate`)의 실행 기록이 없다 |
| `409` | `OPS-003` | 실행 기록이 `FAILED` 상태가 아니다(`SUCCESS`, `RUNNING`). 동시에 두 번 오면 한쪽이 이 오류를 받는다 |
| `422` | `OPS-001` | `jobName`이 `DAILY_0000`도 등록된 `StandaloneJob`도 아니다 |

## 관리자 오류 코드

| 코드 | 상태 | 메시지 |
| --- | --- | --- |
| `ADMIN-001` | `409` | 이미 사용 중인 관리자 ID입니다. |

## 운영(ops) 오류 코드

| 코드 | 상태 | 메시지 |
| --- | --- | --- |
| `OPS-001` | `422` | 다시 실행할 수 없는 작업입니다. |
| `OPS-002` | `404` | 그 작업의 실행 기록이 없습니다. |
| `OPS-003` | `409` | FAILED 상태가 아니면 재실행할 수 없습니다. |

요청 검증과 JSON 형식 오류 등 `COMMON-*`는 [공통 규약](./api-spec.md#공통-오류-코드), `AUTH-*`는 [인증 오류 코드](./auth-api-spec.md#오류-코드)를 따른다.
