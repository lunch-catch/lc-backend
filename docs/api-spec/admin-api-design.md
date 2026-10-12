# 관리자 (admin) API 설계

관리자 계정 발급·로그인의 내부 처리와 저장 구조를 적는다. 요청·응답·오류는 [admin-api-spec.md](./admin-api-spec.md), 토큰 재발급 내부 처리는 [auth-api-design.md](./auth-api-design.md#관리자)에 있다.

## 구현 근거와 책임

| 기능 | 근거 |
| --- | --- |
| 관리자 계정 발급·로그인 | develop `3ec6bdbbf728d09804801eda2719ea66d7c295e9`, 요구사항 4·5행 |
| 관리자 토큰 재발급 | PR #117 `9c0fdfdd1803d0649559c4d2e0edadcd0d43df25`, 요구사항 6행 |

첨부 `admin.md`를 위 구현과 대조하여 계약과 내부 처리로 나누었다. 자료에 포함된 PR #119 로그아웃은 이식 범위에서 제외했다. 관리자 계정과 상태 검증은 `admin`, 공통 쿠키·JWT·Redis 토큰 저장은 `auth`, 감사 로그 저장은 `ops`가 소유한다.

## 관리자 계정 발급

1. 발급자 권한과 Access Token 폐기 기준을 확인하고 로그인 ID 중복을 검사한다.
2. DB 트랜잭션 밖에서 초기 비밀번호를 BCrypt로 해싱한다. 비밀번호 평문은 저장하지 않는다.
3. DB 트랜잭션에서 발급자의 현재 상태·권한을 재확인하고 새 계정을 `ACTIVE`로 저장한다.
4. 계정 저장과 `ADMIN_ACCOUNT_CREATE` 감사 로그 저장을 같은 트랜잭션으로 처리한다. 하나가 실패하면 함께 롤백한다.

동시 요청의 로그인 ID 중복도 DB UNIQUE 제약으로 차단한다.

## 관리자 로그인

1. 관리자 계정을 조회하고 BCrypt로 비밀번호를 비교한다. 없는 계정에는 더미 해시로 비교한다. UTF-8 72바이트 초과 비밀번호도 더미 비교 후 동일한 실패로 처리한다.
2. 계정 없음, 비밀번호 불일치, 비활성 또는 관리자 역할이 아닌 경우 모두 동일한 `AUTH-001`을 반환한다.
3. 짧은 DB 트랜잭션에서 행 잠금 후 계정 상태·역할·검증한 비밀번호 해시를 다시 확인한다.
4. 새 Refresh Token의 SHA-256 해시와 만료 시각을 DB에 저장하고 발급 순번을 증가시킨다.
5. Access Token을 생성하고 현재 DB 상태를 확인한 뒤 Refresh Token을 Redis 캐시에 게시한다. 발급 순번 비교로 뒤늦은 이전 토큰 게시가 최신 상태를 덮어쓰지 않게 한다.
6. Redis 게시 실패만으로 로그인을 실패시키지는 않는다. 필수 DB 저장·확인 실패는 `503 AUTH-002`로 처리한다.
7. 성공 시 토큰 쿠키와 관리자 정보를 반환한다.

DB는 관리자별 현재 Refresh Token 해시 하나를 보관한다. 새 로그인으로 이전 Refresh Token은 대체되지만, 로그인 자체가 이전 Access Token을 모두 폐기하는 것은 아니다. 계정별 로그인 실패 횟수 잠금은 이 구현에 포함하지 않는다.

로그인 감사는 `AdminLoginAuditFilter`가 `ADMIN_LOGIN` 이벤트로 시각·IP·성공 여부·HTTP 상태·소요 시간을 운영 로그에 기록한다. `audit_log` 테이블에 로그인 이벤트를 저장하는 방식은 아니다. 비밀번호와 토큰 원문은 기록하지 않는다.

## 저장 구조와 감사 로그

### `admin`

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
| 로그인·재발급·재사용 폐기 순번 | `refresh_token_issuance_version` |
| 비활성화 시각 | `deleted_at` |
| 생성·수정 시각 | `created_at`, `updated_at` |

- `login_id`는 UNIQUE다.
- `role`은 `ADMIN`, `SUPER_ADMIN`, `status`는 `ACTIVE`, `DELETED`다.
- Refresh Token 해시와 만료 시각은 둘 다 `NULL`이거나 둘 다 값이 있어야 한다.
- `DELETED` 상태는 `deleted_at`이 있어야 하고 Refresh Token 해시가 없어야 한다. 그 외 상태는 `deleted_at`이 없어야 한다.
- 발급 순번은 기본값 `0`의 음이 아닌 BIGINT다. 마이그레이션 `V2__admin_refresh_token_issuance_version.sql`에서 추가한다.
- Refresh Token 원문은 DB에 저장하지 않는다. Redis의 토큰 캐시·회전 기록·발급 순번은 공통 인증 모듈이 관리한다.

### `audit_log` (`ops` 소유)

| 이벤트 | `admin_id` | `action` | `target` | `detail` | 실패 정책 |
| --- | --- | --- | --- | --- | --- |
| 계정 발급 성공 | 발급자 ID | `ADMIN_ACCOUNT_CREATE` | 새 계정의 `loginId` | `role=ADMIN` 또는 `role=SUPER_ADMIN` | 계정 저장과 함께 롤백 |
| 토큰 재발급 성공 | 대상 관리자 ID | `ADMIN_TOKEN_REISSUE` | 관리자 ID 문자열 | `result=SUCCESS` | DB 회전과 함께 롤백 |
| 재발급 중 기록 대상 실패 | 대상 관리자 ID | `ADMIN_TOKEN_REISSUE` | 관리자 ID 문자열 | `result=FAILURE;reason=<사유>` | 별도 트랜잭션, 감사 실패 시 운영 로그 |

재발급 실패 감사는 식별 가능한 관리자에 대해 `REUSE_DETECTED`, `ROTATION_RESULT_UNKNOWN`, `DB_ROTATION_FAILED`, `DB_FALLBACK_FAILED`, `COMPENSATION_FAILED` 등의 사유로 기록한다. 토큰 누락·형식 오류 등 모든 실패에 감사 로그가 생기는 것은 아니다.
