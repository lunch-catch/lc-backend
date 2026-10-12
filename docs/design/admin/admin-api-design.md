# 관리자 API 설계

HTTP 요청·응답은 [관리자 명세](../../api-spec/admin-api-spec.md)와 [인증 명세](../../api-spec/auth-api-spec.md#관리자)를 따른다. 이 문서는 관리자 서비스의 트랜잭션, 저장 구조와 장애 처리를 정의한다.

## 구현 근거

2026-10-12 확인 기준이다. 별도 기능 브랜치의 설명은 병합 완료나 배포 완료를 의미하지 않는다.

| 기능 | 확인 대상 | 커밋 |
| --- | --- | --- |
| 계정 발급·로그인 | [develop](https://github.com/lunch-catch/lc-backend/tree/a828caf82670e5942ede223a80b44d66778d9912) | `a828caf82670e5942ede223a80b44d66778d9912` |
| 재발급 | [PR #117](https://github.com/lunch-catch/lc-backend/pull/117) | `d332bdad7281b8d4325f9970e2e1caed70771f49` |
| 로그아웃 | 로컬 feat/admin-logout, [PR #119](https://github.com/lunch-catch/lc-backend/pull/119) | `da3cfbe3a6020e93d26caedeb6307870ed3e6338` |

현재 로컬에는 계정 발급·로그인·로그아웃이 구현되어 있다. 재발급 설계는 feat/admin-token-refresh 브랜치의 별도 구현을 설명한다.

관리자 계정·상태 확인과 서비스 흐름은 `admin`, 공통 토큰·쿠키·인증 저장소는 `auth`, 감사 로그 저장은 `ops`가 담당한다.

## 계정 발급

계약은 [관리자 계정 발급](../../api-spec/admin-api-spec.md#관리자-계정-발급)을 따른다.

### 서버가 하는 일

1. 발급자 권한과 Access Token 폐기 기준을 확인하고 로그인 ID 중복을 검사한다.
2. DB 트랜잭션 밖에서 초기 비밀번호를 BCrypt로 해싱한다. 비밀번호 평문은 저장하지 않는다.
3. DB 트랜잭션에서 발급자의 현재 상태·권한을 재확인하고 새 계정을 `ACTIVE`로 저장한다.
4. 계정 저장과 `ADMIN_ACCOUNT_CREATE` 감사 로그 저장을 같은 트랜잭션으로 처리한다. 하나가 실패하면 함께 롤백한다.

### 정한 이유

비밀번호 해싱을 트랜잭션 밖에서 수행하여 행 잠금 시간을 줄인다. 계정과 감사 로그를 함께 저장하여 감사 기록이 없는 계정 발급을 막는다.
동시 요청의 로그인 ID 중복도 DB UNIQUE 제약으로 차단한다.

## 로그인

계약은 [관리자 로그인](../../api-spec/admin-api-spec.md#관리자-로그인)을 따른다.

### 서버가 하는 일

1. 관리자 계정을 조회하고 BCrypt로 비밀번호를 비교한다. 없는 계정에는 더미 해시로 비교한다. UTF-8 72바이트 초과 비밀번호도 더미 비교 후 동일한 실패로 처리한다.
2. 계정 없음, 비밀번호 불일치, 비활성 또는 관리자 역할이 아닌 경우 모두 동일한 `AUTH-001`을 반환한다.
3. 짧은 DB 트랜잭션에서 행 잠금 후 계정 상태·역할·검증한 비밀번호 해시를 다시 확인한다.
4. 새 Refresh Token의 SHA-256 해시와 만료 시각을 DB에 저장하고 발급 순번을 증가시킨다.
5. Access Token을 생성하고 현재 DB 상태를 확인한 뒤 Refresh Token을 Redis 캐시에 게시한다. 발급 순번 비교로 뒤늦은 이전 토큰 게시가 최신 상태를 덮어쓰지 않게 한다.
6. Redis 게시 실패만으로 로그인을 실패시키지는 않는다. 필수 DB 저장·확인 실패는 `503 AUTH-002`로 처리한다.
7. 성공 시 토큰 쿠키와 관리자 정보를 반환한다.

### 정한 이유

DB는 관리자별 현재 Refresh Token 해시 하나를 보관한다. 새 로그인으로 이전 Refresh Token은 대체되지만, 로그인 자체가 이전 Access Token을 모두 폐기하는 것은 아니다. 계정별 로그인 실패 횟수 잠금은 이 구현에 포함하지 않는다.

## 토큰 재발급

계약은 [인증 명세의 관리자 절](../../api-spec/auth-api-spec.md#관리자)을 따른다.

### 서버가 하는 일

1. Refresh Token 형식을 검사하고 Redis에서 조회·원자적 비교 및 회전을 수행한다.
2. Redis 조회 실패·캐시 부재 또는 회전 중 타임아웃 이외의 저장소 예외에서는 DB 백업을 통한 회전을 시도한다. 정상적인 Redis 회전 결과가 실패라면 DB로 우회하지 않는다.
3. DB 행 잠금 아래 현재 해시·만료 시각·`ACTIVE` 상태·관리자 역할을 확인한다. DB의 현재 토큰과 일치해야 새 토큰으로 교체할 수 있다.
4. 새 Refresh Token 해시·만료 시각·발급 순번과 `ADMIN_TOKEN_REISSUE` 성공 감사 로그를 같은 트랜잭션에서 저장한다.
5. Redis에 새 토큰 상태를 게시하고 새 Access Token을 발급한다. DB 회전이 성공한 후 Redis 게시 실패만 발생한 경우에는 성공을 유지한다.

### 장애 처리

회전 타임아웃은 새 토큰의 Redis 상태를 확인한 경우에만 계속 처리한다. 결과를 확인할 수 없으면 `503 AUTH-002`를 반환한다. DB 회전 결과가 불확실할 때도 커밋 확인·롤백 복구·보상 폐기를 수행하며, 확정하지 못한 결과를 성공으로 반환하지 않는다.

Redis에서 이미 교체된 토큰의 재사용을 감지하면 해당 관리자의 Refresh Token을 폐기하고 `401 AUTH-004`를 반환한다. 폐기 자체가 실패하면 `503 AUTH-002`다. 이 재사용 처리에는 Access Token 폐기 기준 갱신이 포함되지 않는다. Redis 재사용 기록 없이 DB 백업에서 현재 해시가 불일치한 경우에는 `AUTH-003`으로 처리하므로 모든 오래된 토큰 요청이 `AUTH-004`인 것은 아니다.

## 로그아웃

계약은 [인증 명세의 관리자 절](../../api-spec/auth-api-spec.md#관리자)을 따른다.

### 서버가 하는 일

1. DB 행 잠금 아래 계정이 존재하고 `ACTIVE`이며 인증 역할과 현재 관리자 역할이 일치하는지 확인한다.
2. DB Refresh Token 해시·만료 시각을 비우고 발급 순번을 증가시킨다.
3. Redis에서 폐기 순번보다 오래된 토큰과 현재 폐기 대상 해시를 제거한다. 지연된 이전 로그인·재발급 게시도 차단하며 더 최신 발급 상태는 보존한다.
4. 계정·역할별 Access Token 폐기 기준 시각을 Redis에 저장한다. 이 시각 이전에 발급된 Access Token은 이후 인증 시 거부한다. 기준의 TTL은 Access Token 수명이다.
5. 필수 폐기 완료 후 별도 트랜잭션으로 `ADMIN_LOGOUT` 성공 감사 로그를 기록한다. 감사 저장만 실패하면 운영 로그를 남기고 로그아웃 성공을 유지한다.
6. 두 인증 쿠키를 원래 경로와 동일한 경로에서 `Max-Age=0`으로 삭제한다.

### 정한 이유와 장애 처리

DB 폐기와 Redis 정리는 하나의 트랜잭션으로 묶이지 않는다. 필수 처리의 부분 실패가 남을 수 있으며 HTTP 응답 정책은 인증 명세를 따른다.
감사 로그는 필수 토큰 폐기 후 별도 트랜잭션으로 기록한다. 감사 저장 실패가 이미 완료된 폐기를 취소하지 않게 한다.
폐기 기준 조회의 LENIENT·REQUIRED 정책과 클라이언트가 받는 오류는 인증 명세를 따른다.

## 아직 정해지지 않은 것과 통합 시 확인 사항

`refresh_token_issuance_version`은 Refresh Token 상태와 Redis 게시 순서를 보호한다. 현재 Access Token에는 DB 발급 순번을 연결한 검증이 없으며 폐기 기준 시각과 JWT `iat`로 판단한다.

- JWT의 초 단위 `iat` 때문에 로그아웃과 같은 초에 다시 로그인한 Access Token도 거부될 수 있다. 다음 초 이후 재로그인이 필요하다.
- 재발급 서비스는 DB 회전·Redis 게시 이후 현재 시각으로 Access Token을 생성한다. DB 재발급 커밋 후 로그아웃이 완료되고 지연된 요청이 JWT를 생성하면 폐기 기준보다 늦은 `iat`를 가질 수 있다. 로그인에도 DB 발급과 JWT 생성 사이에 같은 종류의 틈이 있다. Refresh Token 발급 순번과 `saveIfNewer`만으로 이 문제는 해결되지 않는다.
- 따라서 관리자 토큰 재발급 기능과 통합할 때 DB에서 확정한 발급 상태와 Access Token 검증을 연결하는 보완이 필요하다. 이 문서 이식은 해당 보완을 구현하거나 완료로 선언하지 않는다.

## 저장 구조와 감사 로그

### `admin`

로그인 응답의 `adminId`, `name`, `role`은 각각 `admin_id`, `name`, `role` 컬럼에 대응한다.

| 필드·처리 | DB 컬럼 |
| --- | --- |
| 관리자 식별자 | `admin_id` |
| 로그인 ID | `login_id` |
| BCrypt 비밀번호 해시 | `password_hash` |
| 이름 | `name` |
| 역할 | `role` |
| 상태 | `status` |
| Refresh Token SHA-256 해시 | `refresh_token_hash` |
| Refresh Token 만료 시각 | `refresh_token_expires_at` |
| 로그인·재발급·폐기 순번 | `refresh_token_issuance_version` |
| 비활성화 시각 | `deleted_at` |
| 생성·수정 시각 | `created_at`, `updated_at` |

- `login_id`는 UNIQUE다.
- `role`은 `ADMIN`, `SUPER_ADMIN`, `status`는 `ACTIVE`, `DELETED`다.
- Refresh Token 해시와 만료 시각은 둘 다 `NULL`이거나 둘 다 값이 있어야 한다.
- `DELETED` 상태는 `deleted_at`이 있어야 하고 Refresh Token 해시가 없어야 한다. 그 외 상태는 `deleted_at`이 없어야 한다.
- 발급 순번은 기본값 `0`의 음이 아닌 BIGINT다. 마이그레이션 `V2__admin_refresh_token_issuance_version.sql`에서 추가한다.
- Refresh Token 원문은 DB에 저장하지 않는다. Redis의 토큰 캐시·회전 기록·발급 순번과 Access Token 폐기 기준은 공통 인증 모듈이 관리한다.

### `audit_log` (`ops` 소유)

| 이벤트 | `admin_id` | `action` | `target` | `detail` | 실패 정책 |
| --- | --- | --- | --- | --- | --- |
| 계정 발급 성공 | 발급자 ID | `ADMIN_ACCOUNT_CREATE` | 새 계정의 `loginId` | `role=ADMIN` 또는 `role=SUPER_ADMIN` | 계정 저장과 함께 롤백 |
| 토큰 재발급 성공 | 대상 관리자 ID | `ADMIN_TOKEN_REISSUE` | 관리자 ID 문자열 | `result=SUCCESS` | DB 회전과 함께 롤백 |
| 재발급 중 기록 대상 실패 | 대상 관리자 ID | `ADMIN_TOKEN_REISSUE` | 관리자 ID 문자열 | `result=FAILURE;reason=<사유>` | 별도 트랜잭션, 감사 실패 시 운영 로그 |
| 로그아웃 성공 | 대상 관리자 ID | `ADMIN_LOGOUT` | 관리자 ID 문자열 | `result=SUCCESS` | 폐기 후 별도 트랜잭션, 감사 실패만으로 성공 취소하지 않음 |

재발급 실패 감사는 식별 가능한 관리자에 대해 `REUSE_DETECTED`, `ROTATION_RESULT_UNKNOWN`, `DB_ROTATION_FAILED`, `DB_FALLBACK_FAILED`, `COMPENSATION_FAILED` 등의 사유로 기록한다. 토큰 누락·형식 오류 등 모든 실패에 감사 로그가 생기는 것은 아니다.