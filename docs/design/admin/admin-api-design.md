# 관리자 API 설계

HTTP 요청·응답은 [관리자 명세](../../api-spec/admin-api-spec.md)와 [인증 명세](../../api-spec/auth-api-spec.md#관리자)를 따른다. 이 문서는 관리자 계정 발급·로그인·토큰 재발급·로그아웃의 트랜잭션, 저장 구조와 장애 처리를 정의한다.

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
5. DB에서 확정한 발급 순번을 Access Token의 `issuanceVersion`에 넣고 현재 DB 상태를 확인한 뒤 Refresh Token을 Redis 캐시에 게시한다. 발급 순번 비교로 뒤늦은 이전 토큰 게시가 최신 상태를 덮어쓰지 않게 한다.
6. Redis 게시 실패만으로 로그인을 실패시키지는 않는다. 필수 DB 저장·확인 실패는 `503 AUTH-002`로 처리한다.
7. 성공 시 토큰 쿠키와 관리자 정보를 반환한다.

### 정한 이유

DB는 관리자별 현재 Refresh Token 해시 하나를 보관한다. 새 로그인으로 이전 Refresh Token은 대체되지만, 로그인 자체가 이전 Access Token을 모두 폐기하는 것은 아니다. 계정별 로그인 실패 횟수 잠금은 이 구현에 포함하지 않는다.

로그인 감사는 `AdminLoginAuditFilter`가 `ADMIN_LOGIN` 이벤트로 시각·IP·성공 여부·HTTP 상태·소요 시간을 운영 로그에 기록한다. `audit_log` 테이블에는 저장하지 않으며 비밀번호와 토큰 원문도 기록하지 않는다.

## 토큰 재발급

회전·복구·재사용 처리 절차는 [인증 설계의 관리자 토큰 재발급 절](../auth/auth-api-design.md#토큰-재발급)을 따른다. Access Token 발급 순번 검증은 아래 [발급 순번 검증과 배포](#발급-순번-검증과-배포)를 따른다.

## 로그아웃

계약은 [인증 명세의 관리자 절](../../api-spec/auth-api-spec.md#관리자)을 따른다.

### 서버가 하는 일

1. DB 행 잠금 아래 계정이 존재하고 `ACTIVE`이며 인증 역할과 현재 관리자 역할이 일치하는지 확인한다.
2. DB Refresh Token 해시·만료 시각을 비우고 발급 순번을 증가시킨다. 같은 트랜잭션에서 Access Token 폐기 순번과 기존 JWT 폐기 시각을 저장한다.
3. Redis에서 폐기 순번보다 오래된 토큰과 현재 폐기 대상 해시를 제거한다. 지연된 이전 로그인·재발급 게시도 차단하며 더 최신 발급 상태는 보존한다.
4. 계정·역할별 Access Token 폐기 순번을 Redis에 단조 증가하도록 저장한다. JWT의 발급 순번이 폐기 순번 이하면 거부한다. JWT 생성 지연에는 상한이 없으므로 폐기 순번에는 TTL을 두지 않는다. 순번 없는 기존 JWT는 호환용 폐기 시각으로 검증한다.
5. 필수 폐기 완료 후 별도 트랜잭션으로 `ADMIN_LOGOUT` 성공 감사 로그를 기록한다. 감사 저장만 실패하면 운영 로그를 남기고 로그아웃 성공을 유지한다.
6. 두 인증 쿠키를 원래 경로와 동일한 경로에서 `Max-Age=0`으로 삭제한다.

### 정한 이유와 장애 처리

DB 폐기와 Redis 정리는 하나의 트랜잭션으로 묶이지 않는다. DB 커밋만으로 Refresh Token과 두 형식의 Access Token 폐기가 확정된다. Redis 정리가 실패해도 인증은 DB 폐기 기준을 먼저 확인하므로 미완료된 Redis 저장을 HTTP 재시도로 복구해야 하는 보안 공백이 없다. Redis 정리 실패 시 응답은 기존대로 AUTH-002이며 성공 감사는 남기지 않는다. 남은 캐시 정리는 토큰 허용 여부를 결정하지 않는다.
감사 로그는 필수 토큰 폐기 후 별도 트랜잭션으로 기록한다. 감사 저장 실패가 이미 완료된 폐기를 취소하지 않게 한다.
폐기 기준 조회의 LENIENT·REQUIRED 정책과 클라이언트가 받는 오류는 인증 명세를 따른다.

## 발급 순번 검증과 배포

로그인과 재발급은 DB에서 확정한 `refresh_token_issuance_version`을 JWT의 `issuanceVersion`에 넣는다. 로그아웃보다 앞서 승인된 요청이 뒤늦게 JWT를 생성해도 이전 순번이므로 거부한다. 같은 초의 재로그인은 더 큰 순번이므로 허용한다.

`AdminTokenService.result`가 `RotationState.version()`을 `AdminAccessTokenClaims.forVersion`으로 변환해 JWT 생성에 전달한다. 순번 없는 기존 관리자 JWT도 DB 폐기 시각을 먼저 확인한 뒤 공통 모듈의 기존 시각 기준으로 검증한다. 새 관리자 로그인과 재발급은 반드시 DB 순번을 JWT에 넣는다. 이전 서버와 새 서버를 섞어 운영하면 이전 서버가 순번을 검증하지 못하므로 함께 교체한다.

DB의 access_token_revoked_version과 access_token_valid_after가 폐기의 기준이다. 모든 관리자 Access Token 검증은 별도 읽기 트랜잭션으로 DB를 먼저 확인한다. Redis 키가 없거나 Redis가 장애여도 DB에서 폐기한 토큰은 허용하지 않는다. DB 조회 장애는 LENIENT에서도 인증을 거부하고 REQUIRED에서는 AUTH-002를 반환한다. Redis 조회 장애의 LENIENT·REQUIRED 정책은 DB 검증을 통과한 토큰에만 적용한다. 관리자 인증 요청마다 DB 조회가 추가된다.


### 배포와 롤백 절차

현재 backend의 deploy.yml은 infra/scripts/deploy.sh를 호출하는 롤링 배포다. 이 워크플로만으로 구버전과의 혼용 금지를 보장할 수 없다. 실제 운영 배포·롤백은 아직 검증하지 않았다.

1. 관리자 인증 경로와 관리자 토큰을 허용하는 모든 보호 경로의 외부 트래픽을 차단한다. 실행 중인 관리자 로그인·재발급·로그아웃 요청을 종료하고 구버전 인스턴스를 모두 중지한다.
2. admin V3 마이그레이션을 적용한다. 기존 발급 순번까지 폐기하고 기존 JWT 폐기 시각을 설정하므로 배포 전 Access Token은 무효화된다. 마이그레이션 DB 세션의 시간대는 애플리케이션과 같은 Asia/Seoul인지 확인한다.
3. DB 폐기 검증이 포함된 버전으로 모든 인스턴스를 교체한다. 인스턴스별 이미지 SHA와 구버전 인스턴스가 0개인지 확인한다.
4. 새 로그인 성공, 배포 전 JWT 거부, 로그아웃 JWT 거부를 확인한다. 폐기 Redis 키 삭제 후에도 거부되는지 확인한 뒤 트래픽을 재개한다.
5. 롤백은 DB 폐기 검증을 유지하는 이미지로만 수행한다. V3 컬럼과 폐기 데이터를 보존한다. 해당 검증이 없는 구버전으로 돌아가야 한다면 트래픽 차단을 유지하고 관리자 JWT 서명 키를 교체하여 기존 JWT를 전부 무효화한다. 공용 키를 사용하면 다른 역할도 재로그인이 필요하다. 모든 구버전 인스턴스가 새 키만 사용하는지 검증한 뒤 트래픽을 재개한다.

운영자는 차단·재개 시각, 인스턴스별 이미지 SHA, V3 적용 결과, 검증 응답을 배포 기록에 남긴다. 해당 기록이 없으면 혼용 없는 배포를 완료했다고 판단하지 않는다.

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
| Access Token 폐기 순번 상한 | `access_token_revoked_version` |
| 기존 JWT 폐기 시각 | `access_token_valid_after` |
| 비활성화 시각 | `deleted_at` |
| 생성·수정 시각 | `created_at`, `updated_at` |

- `login_id`는 UNIQUE다.
- `role`은 `ADMIN`, `SUPER_ADMIN`, `status`는 `ACTIVE`, `DELETED`다.
- Refresh Token 해시와 만료 시각은 둘 다 `NULL`이거나 둘 다 값이 있어야 한다.
- `DELETED` 상태는 `deleted_at`이 있어야 하고 Refresh Token 해시가 없어야 한다. 그 외 상태는 `deleted_at`이 없어야 한다.
- 발급 순번은 기본값 `0`의 음이 아닌 BIGINT다. 마이그레이션 `V2__admin_refresh_token_issuance_version.sql`에서 추가한다.
- Refresh Token 원문은 DB에 저장하지 않는다. Redis의 Refresh Token 캐시·회전 기록은 공통 인증 모듈이 관리한다. 관리자 Access Token 폐기 순번은 관리자 모듈이 관리한다.

### `audit_log` (`ops` 소유)

| 이벤트 | `admin_id` | `action` | `target` | `detail` | 실패 정책 |
| --- | --- | --- | --- | --- | --- |
| 계정 발급 성공 | 발급자 ID | `ADMIN_ACCOUNT_CREATE` | 새 계정의 `loginId` | `role=ADMIN` 또는 `role=SUPER_ADMIN` | 계정 저장과 함께 롤백 |
| 토큰 재발급 성공 | 대상 관리자 ID | `ADMIN_TOKEN_REISSUE` | 관리자 ID 문자열 | `result=SUCCESS` | DB 회전과 함께 롤백 |
| 재발급 중 기록 대상 실패 | 대상 관리자 ID | `ADMIN_TOKEN_REISSUE` | 관리자 ID 문자열 | `result=FAILURE;reason=<사유>` | 별도 트랜잭션, 감사 실패 시 운영 로그 |
| 로그아웃 성공 | 대상 관리자 ID | `ADMIN_LOGOUT` | 관리자 ID 문자열 | `result=SUCCESS` | 폐기 후 별도 트랜잭션, 감사 실패만으로 성공 취소하지 않음 |

재발급 실패 감사는 식별 가능한 관리자에 대해 `REUSE_DETECTED`, `ROTATION_RESULT_UNKNOWN`, `DB_ROTATION_FAILED`, `DB_FALLBACK_FAILED`, `COMPENSATION_FAILED` 등의 사유로 기록한다. 토큰 누락·형식 오류 등 모든 실패에 감사 로그가 생기는 것은 아니다.
