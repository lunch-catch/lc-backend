# 관리자 (admin) API 명세

관리자 계정 발급·목록 조회·로그인·토큰 재발급·로그아웃, 그리고 운영(`ops`)이 소유한 배치 작업 수동 재실행과 배치 실행 이력 조회 API를 안내한다. 계정 발급과 로그인, 배치 수동 재실행과 실행 이력 조회의 요청·응답·오류는 이 문서에서 정의한다. 토큰·쿠키 정책과 재발급·로그아웃의 상세 계약은 [auth-api-spec.md](./auth-api-spec.md)를 따른다. 공통 규약은 [api-spec.md](./api-spec.md)를 따른다. 관리자 계정 기능의 서버 처리와 저장 구조는 [admin-api-design.md](../design/admin/admin-api-design.md)에, 배치 수동 재실행의 서버 처리는 [batch-execution-retry-api-design.md](../design/ops/batch-execution-retry-api-design.md)에, 실행 이력 조회의 서버 처리는 [batch-execution-history-api-design.md](../design/ops/batch-execution-history-api-design.md)에 있다.

## 목록과 구현 범위

관리자 API의 전체 문서 범위는 계정 발급·목록 조회·로그인·토큰 재발급·로그아웃, 점주와 사용자 관리, 입점 신청 조회, 플랫폼 설정값, 감사 로그, 배치 작업 수동 재실행을 포함한다. 아래 표는 현재 이 문서에서 안내하는 구현된 관리자 API다. 나머지 관리 기능은 전체 문서 범위에 포함되지만, 이 목록만으로 구현 완료를 의미하지는 않는다.

| 메서드 | 경로 | 기능 | 인증 및 권한 |
| --- | --- | --- | --- |
| `POST` | `/v1/admin/admins` | 관리자 계정 발급 | Access Token, `SUPER_ADMIN` |
| `GET` | `/v1/admin/admins` | 관리자 계정 목록 조회 | Access Token, `SUPER_ADMIN` |
| `POST` | `/v1/admin/auth/tokens` | 관리자 로그인 | 기존 토큰 불필요 |
| `DELETE` | `/v1/admin/auth/tokens` | 관리자 로그아웃 | Access Token, `ADMIN` 또는 `SUPER_ADMIN` |
| `POST` | `/v1/admin/auth/tokens:refresh` | 관리자 토큰 재발급 | Refresh Token. 상세 계약은 [인증 명세](./auth-api-spec.md#관리자) |
| `POST` | `/v1/admin/batch-executions/{jobName}/{businessDate}:retry` | 배치 작업 수동 재실행 | Access Token, `ADMIN` 또는 `SUPER_ADMIN` |
| `GET` | `/v1/admin/batch-executions` | 배치 실행 이력 조회 | Access Token, `ADMIN` 또는 `SUPER_ADMIN` |


## 로그인·재발급 요청 제한

인증 요청 제한 필터는 IP별 1분 고정 윈도우에서 10회를 허용한다. 대상 로그인·재발급 경로들이 같은 IP 카운터를 공유하므로 관리자 API별로 각각 10회가 아니다. 초과 시 `429`와 남은 대기 시간(초)의 `Retry-After`를 반환하며, 현재 구현은 이 응답에 JSON 본문이나 오류 코드를 넣지 않는다. 캐시 장애로 횟수를 확인하지 못하면 요청을 통과시킨다. 계정별 로그인 실패 잠금과는 별개다.

## 관리자 계정 발급

### `POST /v1/admin/admins`

최고 관리자가 `ADMIN` 또는 `SUPER_ADMIN` 계정을 발급한다.

### 인증 및 권한

- 유효한 `adminAccessToken` 쿠키가 필요하며 역할은 `SUPER_ADMIN`이어야 한다.
- 서버는 JWT 권한 외에 DB의 발급자 계정이 현재 `ACTIVE`, `SUPER_ADMIN`인지 다시 확인한다.
- Access Token 폐기 기준 조회는 필수다. 저장소 장애 시 발급을 진행하지 않고 `503 AUTH-002`를 반환한다.

### 요청

```http
POST /v1/admin/admins
Content-Type: application/json
Cookie: adminAccessToken=<JWT>
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

## 관리자 계정 목록 조회

### `GET /v1/admin/admins`

최고 관리자가 본인을 포함한 활성(`ACTIVE`) 관리자 계정을 조회한다. `ADMIN`, `SUPER_ADMIN` 모두 포함하며 비활성 계정은 제외한다.

### 요청

```http
GET /v1/admin/admins?keyword=admin
Cookie: adminAccessToken=<JWT>
```

| 쿼리 파라미터 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `keyword` | string | 아니오 | ID 또는 이름 부분 일치. 앞뒤 공백을 제거하고 영문 대소문자를 구분하지 않는다. 생략·빈 문자열·공백뿐인 값은 전체 조회한다. `%`, `_`, `!`는 문자 그대로 검색한다 |

최근 계정 발급순(`created_at` 내림차순)이며 생성 시각이 같으면 내부 PK 내림차순이다. 계정 수가 적어 페이지네이션 없이 전체 반환한다. 발급 시각은 응답에 포함하지 않는다.

### 응답 `200 OK`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": [
    { "loginId": "admin01", "name": "홍길동", "role": "ADMIN" }
  ]
}
```

각 항목의 `loginId`, `name`, `role`은 null이 아닌 문자열이다. `role`은 `ADMIN` 또는 `SUPER_ADMIN`이다. 결과가 없으면 `data`는 빈 배열(`[]`)이다.

### 인증 및 오류

유효한 Access Token과 현재 DB 계정의 `ACTIVE`·`SUPER_ADMIN` 상태를 확인한다.

| 상태 | 코드 | 조건 |
| --- | --- | --- |
| `401` | `AUTH-005` | Access Token 없음·무효·만료·폐기 |
| `403` | `AUTH-006` | 최고 관리자 권한 없음 또는 현재 DB 계정 없음·비활성·권한 변경 |
| `503` | `AUTH-002` | 필수 토큰 폐기 기준 조회 장애 |

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
Set-Cookie: adminAccessToken=<JWT>; Path=/; Max-Age=1800; HttpOnly; SameSite=Strict
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

- 유효한 `adminAccessToken` 쿠키가 필요하며 역할은 `ADMIN` 또는 `SUPER_ADMIN`이어야 한다.

### 요청

```http
POST /v1/admin/batch-executions/DAILY_0000/2026-09-30:retry
Cookie: adminAccessToken=<JWT>
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

실제로 성공했는지 실패했는지는 이 응답에 없다. 감사 로그와 [배치 실행 이력 조회](#배치-실행-이력-조회)로 확인한다.

### 오류

| 상태 | 코드 | 조건 |
| --- | --- | --- |
| `401` | `AUTH-005` | Access Token 없음·무효·만료 또는 폐기 기준에 의해 무효 |
| `403` | `AUTH-006` | 관리자 권한 없음 |
| `404` | `OPS-002` | 그 (`jobName`, `businessDate`)의 실행 기록이 없다 |
| `409` | `OPS-003` | 실행 기록이 `FAILED` 상태가 아니다(`SUCCESS`, `RUNNING`). 동시에 두 번 오면 한쪽이 이 오류를 받는다 |
| `422` | `OPS-001` | `jobName`이 `DAILY_0000`도 등록된 `StandaloneJob`도 아니다 |

## 배치 실행 이력 조회

### `GET /v1/admin/batch-executions`

관리자가 영업일 범위 안의 배치 실행 기록을 조회한다. 00:00 묶음(`DAILY_0000`)과 그 단계, 묶음 밖 작업, 캠페인 단위 작업(`작업명:캠페인ID`)을 모두 담는다.

**(`jobName`, `businessDate`)마다 한 항목이다.** 서버는 실행 기록을 (작업명, 영업일)마다 한 행으로 두고, 재실행하면 그 행을 새 시도로 덮어쓴다. 그래서 지난 시도의 시각과 실패 사유는 이 응답에 없다. 재실행했다는 사실은 `attemptNo`와 `retryCount`로 보인다.

### 인증 및 권한

- 유효한 `adminAccessToken` 쿠키가 필요하며 역할은 `ADMIN` 또는 `SUPER_ADMIN`이어야 한다.

### 요청

```http
GET /v1/admin/batch-executions?from=2026-09-24&to=2026-09-30&status=FAILED&pageSize=20
Cookie: adminAccessToken=<JWT>
```

| 쿼리 파라미터 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `from` | string (date) | 예 | 조회할 첫 영업일. `YYYY-MM-DD` |
| `to` | string (date) | 예 | 조회할 마지막 영업일. `YYYY-MM-DD`. `from`과 같거나 뒤여야 하고 `from`부터 세어 31일을 넘지 않는다 |
| `jobName` | string | 아니오 | 작업명. 정확히 같은 것만 담는다. 비우면 모든 작업 |
| `status` | string | 아니오 | `RUNNING`, `SUCCESS`, `FAILED` 중 하나. 비우면 모든 상태 |
| `pageSize` | number | 아니오 | 공통 규약. 기본 20, 최대 100 |
| `pageToken` | string | 아니오 | 공통 규약. 앞 응답의 `nextPageToken`을 그대로 보낸다 |

정렬은 `businessDate` 내림차순이고 같은 영업일 안에서는 `runId` 내림차순이다. `sort`는 받지 않는다. 다음 페이지를 받을 때는 `pageToken`과 함께 앞 요청과 같은 조건을 보낸다.

### 응답 `200 OK`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "items": [
      {
        "runId": 301,
        "jobName": "DAILY_0000",
        "businessDate": "2026-09-30",
        "status": "FAILED",
        "startedAt": "2026-09-30T00:00:00+09:00",
        "finishedAt": "2026-09-30T00:03:12+09:00",
        "processedCount": null,
        "executedBy": "batch-02",
        "retryCount": 2,
        "failureReason": "RESERVE_DAILY_BUDGET 단계가 실패했다",
        "attemptNo": 1,
        "retryable": true
      }
    ],
    "nextPageToken": "eyJ..."
  }
}
```

| 응답 필드 | 타입 | null 가능 | 뜻 |
| --- | --- | --- | --- |
| `data.items[].runId` | number (int64) | 아니오 | 실행 기록 ID. 재실행 API 응답의 `runId`와 같은 값이다 |
| `data.items[].jobName` | string | 아니오 | 작업명 |
| `data.items[].businessDate` | string (date) | 아니오 | 처리 대상 영업일. 실행한 시각이 아니다 |
| `data.items[].status` | string | 아니오 | `RUNNING`, `SUCCESS`, `FAILED` |
| `data.items[].startedAt` | string (date-time) | 아니오 | 이 시도를 시작한 시각. 재실행하면 그 시각으로 바뀐다 |
| `data.items[].finishedAt` | string (date-time) | 예 | 끝난 시각. `RUNNING`이면 `null` |
| `data.items[].processedCount` | number | 예 | 처리 건수. 건수의 뜻은 작업마다 다르다. 실행 중이거나 실패했거나 건수를 남기지 않는 작업이면 `null` |
| `data.items[].executedBy` | string | 아니오 | 지금 그 기록을 가진 배치 서버. 이어받으면 이어받은 서버로 바뀐다 |
| `data.items[].retryCount` | number | 아니오 | 이 시도에서 일시적 오류로 자동 재실행한 횟수. 0~2 |
| `data.items[].failureReason` | string | 예 | 실패 사유 원문. 최대 500자. `FAILED`일 때만 값이 있다 |
| `data.items[].attemptNo` | number | 아니오 | 실행 세대. 처음 실행하면 1이고 이어받기와 수동 재실행마다 1씩 오른다 |
| `data.items[].retryable` | boolean | 아니오 | 지금 [배치 작업 수동 재실행](#배치-작업-수동-재실행)을 부를 수 있으면 `true`. `status`가 `FAILED`이고 작업명이 `DAILY_0000`이거나 등록된 묶음 밖 작업일 때만 `true`다 |
| `data.nextPageToken` | string | 예 | 다음 페이지 커서. `null`이면 마지막 페이지다 |

조건에 맞는 기록이 없으면 `items`가 빈 배열이다. `404`가 아니다.

`retryable`이 `false`인 `FAILED` 항목은 00:00 묶음의 개별 단계다. 그 단계는 `DAILY_0000`을 재실행하면 실패한 단계부터 다시 돈다.

### 오류

| 상태 | 코드 | 조건 |
| --- | --- | --- |
| `400` | `OPS-004` | `from`이나 `to`가 없다. `to`가 `from`보다 앞이다. 기간이 31일을 넘는다 |
| `400` | `COMMON-002` | `from`, `to`가 날짜 형식이 아니다. `status`가 세 값이 아니다. `pageSize`가 범위를 벗어난다. `pageToken`을 해석할 수 없다 |
| `401` | `AUTH-005` | Access Token 없음·무효·만료 또는 폐기 기준에 의해 무효 |
| `403` | `AUTH-006` | 관리자 권한 없음 |

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
| `OPS-004` | `400` | 조회 기간이 올바르지 않습니다. |

요청 검증과 JSON 형식 오류 등 `COMMON-*`는 [공통 규약](./api-spec.md#공통-오류-코드), `AUTH-*`는 [인증 오류 코드](./auth-api-spec.md#오류-코드)를 따른다.
