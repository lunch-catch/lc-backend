# 로깅 규칙: 런치캐치 백엔드

이 문서는 공통 로깅 규칙 4판(2026-10-21)을 이 저장소에 적용한 결과다. 문서가 셋으로 나뉘고 역할이 다르다.

| 문서 | 소유 |
|---|---|
| `logging-project-guideline.md` | 이 문서. 업무 코드가 쓰는 도구, 공통 규칙의 예외, 바디 마스킹의 키, 로그를 찾는 쿼리 |
| [logging-project-rationale.md](./logging-project-rationale.md) | 이 문서의 도구와 규칙이 왜 지금 모양인지 |
| [로그_설계.md](../observability/로그_설계.md) | 공통 장치의 구조, 로그의 모양과 설정, 보존 기간, 그리고 그렇게 정한 까닭 |

**장치와 설정의 내용은 설계 문서가 소유한다.** 같은 설명을 이 문서나 근거 문서에 옮기지 않는다.

이 문서가 기대는 외부 자료의 출처와 확인 범위는 [logging-project-rationale.md](./logging-project-rationale.md) 의
17장이 갖는다. 이 문서에는 출처 목록을 복사하지 않는다.

`LOG-*` 번호는 공통 로깅 규칙 4판의 식별자를 유지한다. 공통 문서 3개는 이 저장소의 현재 이력에
없으므로 존재하는 복사본처럼 링크하지 않는다.

---

## 1. 공통 장치

업무 코드에서 공통 장치가 남기는 내용을 다시 쓰지 않는다(`LOG-1-01`). 장치마다 하는 일과 남기는 이벤트는 [로그 설계 2장](../observability/로그_설계.md#2-공통-장치)이 갖는다.

## 2. 업무 코드가 쓰는 도구

| 도구 | 쓰는 때 | 관련 규칙 |
|---|---|---|
| `TransactionLogging.afterCommit(Runnable)` | DB 상태 변경의 성공 로그를 커밋 뒤에 남긴다. 트랜잭션이 없으면 바로 실행한다 | `LOG-6-01` |
| `TransactionLogging.afterFailure(Runnable)` | 롤백됐거나 커밋 결과를 모르는 트랜잭션의 실패를 남긴다. 트랜잭션이 없으면 실행하지 않는다 | `LOG-6-01` |
| `ErrorCode.isExpectedTraffic()` | 정상 운영에서 대량으로 나오는 거절이면 `true` 를 돌려주게 에러 코드에 정의한다. `GlobalExceptionHandler` 가 이 값을 보고 `AccessLogSignal.markExpected()` 를 부른다 | `LOG-5-04` |
| `AccessLogSignal.markExpected(request)` | 위 경로를 거치지 않는 곳에서 직접 표시할 때 쓴다. 표시된 요청의 접근 로그는 4xx, 5xx 여도 DEBUG 로 내려간다 | `LOG-5-04`, `LOG-8-06` |
| `PiiMasker` | 개인정보를 가리는 유일한 유틸이다. `maskEmail`, `maskPhone`, `maskName`, `maskProviderId`(외부 계정 식별자), `maskGeneric`, `redact`(완전히 가림) | `LOG-7-03` |
| `RedisFailureClassifier.causeLabel(e)` | Redis 실패를 `TIMEOUT`, `OTHER` 로 분류한다. 값은 `reason` 키에 넣는다 | `LOG-4-01` |
| `FailureLogDetails.from(e)` | 예외 메시지 없이 원인 타입, HTTP 상태, SQLState, 발생 위치를 뽑는다 | `LOG-4-04` |
| `LogContext.open(job)` | 이어받을 문맥이 없는 실행(스케줄 작업 한 번, 묶음 워커의 묶음 하나)이 `trace_id` 와 `job` 을 MDC 에 넣는다. 에이전트가 그 실행을 감싼 스팬을 열었으면 그 트레이스 ID 를, 없으면 새 값을 쓴다. try-with-resources 로 닫으면 열기 전의 MDC 로 돌아간다 | 없음 |
| `LogContext.wrap(...)` | 요청 스레드가 실행기에 일을 넘길 때 넘기는 순간의 MDC 를 일에 싣는다. `Runnable`, `ExecutorService`, `ScheduledExecutorService` 를 감싸고 가상 스레드 실행기에도 그대로 쓴다. 실행기를 만드는 곳에서 감싼다 | 없음 |
| `ClientIpResolver`(`global/http`) | 클라이언트 IP 를 정한다. `X-Forwarded-For` 의 마지막 항목을 쓰고 없거나 형식이 틀리면 `getRemoteAddr()` 로 돌아간다. `MdcLoggingFilter`, `AdminLoginAuditFilter`, `AuthRateLimitFilter` 가 같은 값을 쓴다. `getRemoteAddr()` 는 이 클래스 밖에서 부르지 않고 `ArchitectureTest` 가 막는다. `X-Forwarded-For` 를 읽는 코드는 리뷰에서 막는다. `server.forward-headers-strategy` 와 `server.tomcat.remoteip.internal-proxies` 는 함께 켜지 않는다. 이유는 [근거 문서 5장](./logging-project-rationale.md#5-clientip-를-검증하는-방식)에 있다 | `LOG-8-05` |
| `LogCapture`(테스트) | 대상 클래스의 로거에 붙어 남긴 로그를 모은다. 로그 테스트에 쓴다 | `LOG-8-09` |
| `AuditLogWriter`(`ops/contract`) | 관리자 행위와 돈이 오간 처리의 감사 기록을 DB 에 쓴다. 관리자 로그아웃, 계정 등록, 토큰 재발급, 포인트 정책, 환불, 포인트 조정, 결제 재조회, 재집계가 이미 쓴다 | `LOG-8-11` |

## 3. 공통 규칙의 예외

| 공통 규칙 | 이 저장소에서 | 이유 |
|---|---|---|
| `LOG-8-07` 의 "장치를 우회해 클라이언트를 직접 만드는 코드는 빌드에서 막는다" | 빌드 대신 리뷰에서 확인한다. `WebClient.builder()`, `WebClient.create()` 를 `global/config` 밖에서 부르는 코드는 리뷰에서 막는다 | [근거 문서 14장](./logging-project-rationale.md#14-webclient-직접-생성을-리뷰로-막는-이유) |

이 표에 없는 차이는 예외가 아니다.

## 4. 설정값

프로필마다의 로그 모양, appender, 로거 레벨을 바꾸는 수단, 보존 기간은 [로그 설계 4장 ~ 8장](../observability/로그_설계.md#4-로그의-모양과-설정)이 갖는다.

## 5. 바디 마스킹

`HttpBodyLoggingFilter` 가 갖는다. 공통 규칙의 기본 키(`LOG-7-08`) 외에 이 저장소가 더하거나
따로 정한 것만 적는다.

**민감 키를 판정하는 방법** 공통 모듈의 `SensitiveKeys` 가 판정한다. 키를 정규화한 뒤 제외,
접미사(`password`, `token`, `secret`, `address`), 정확히 같은 키의 순서로 본다. 민감 키의 값은 자료형과 관계없이
`***REDACTED***` 로 바꾸고, 파싱하지 못한 바디는 전체를 `***UNPARSEABLE_BODY_REDACTED***` 로 남긴다. 이 저장소는
아래 표의 키 가운데 모듈의 기본값에 없는 키를 `app.logging.sensitive-keys.extra` 에 넣고,
`nickname` 을 `app.logging.sensitive-keys.excluded` 에 넣는다.

| 분류 | 이 저장소가 등록한 키 |
|---|---|
| 인증 정보 | `password`, `initialPassword`, `currentPassword`, `newPassword`, `accessToken`, `refreshToken`, `token`, `qrToken`, `idToken`, `secret`, `clientSecret`, `authorization`, `authorizationCode`, `state`, `nonce` |
| 연락처와 신원 | `name`, `representativeName`, `phone`, `sub`(카카오 회원 식별자) |
| 위치 | `address`, `roadAddress`, `locationNickname`, `lat`, `lng`, `latitude`, `longitude`(문자열 값과 숫자 값 모두) |
| 식별 번호 | `businessRegistrationNumber`, `businessNumber`, `providerUserId`, `kakaoUserId`, `user_id`(카카오 연결 끊기 웹훅), `fid`(기기 식별자) |
| 결제 | `paymentKey` |

| 항목 | 값 |
|---|---|
| 가리지 않기로 한 키 | `nickname` |
| 키와 관계없이 찾는 패턴 | 이메일, 휴대폰 번호(`01[016789]-?\d{3,4}-?\d{4}`) |
| 바디 로깅에서 뺀 경로 | `/notifications/stream`(SSE), `/v1/admin/auth/`(관리자 로그인), `/v1/admin/admins`(초기 비밀번호 발급) |
| 바디를 남기는 Content-Type | `application/json`, `text/*`, `application/x-www-form-urlencoded` |
| 남기는 바디의 최대 길이 | 가린 뒤 2000자. 요청 바디는 8192바이트까지 캐싱한다 |

새 민감 필드를 요청이나 응답에 넣으면 접미사 규칙에 걸리는지 먼저 본다. 걸리지 않으면 위 표와 `extra` 설정에 키를 더한다.
빠뜨리면
[`HttpBodyLoggingFilterTest`](../../src/test/java/com/lunchcatch/global/logging/HttpBodyLoggingFilterTest.java) 의 민감 필드 검사 테스트가 실패한다.

### 5.1 스택 대신 원인 정보로 남기는 외부 예외

공통 규칙 `LOG-7-13` 의 대상이다. 아래 예외는 메시지에 민감한 값이 들어갈 수 있다. 예외를 끝내는 곳에서
스택을 남기지 않고 `FailureLogDetails` 가 뽑는 원인 정보(`exceptionType`, `httpStatus`, `sqlState`,
`sqlErrorCode`, `origin`)만 키로 펼쳐 남긴다. 원인 체인 안에 이 예외가 있어도 같다.

| 예외 | 메시지에 들어가는 값 | 이 저장소에서 나오는 곳 |
|---|---|---|
| `WebClientResponseException` 과 하위 클래스 | 쿼리 문자열까지 포함한 요청 URL | 카카오 토큰 교환, 연결 끊기, 토스 승인과 조회 |
| `DataIntegrityViolationException` 과 원인인 `SQLException` | 실행한 SQL 과 위반한 값(`Duplicate entry '…'`) | 저장소 계층의 제약 위반 처리 |
| `io.jsonwebtoken.JwtException` 과 하위 클래스(jjwt) | 메시지에 무엇이 들어가는지 확인하지 않았다. 토큰과 서명 검증 정보를 다루는 예외라서 미리 넣었다 | `JwtTokenProvider.validateToken` 이 잡아 `false` 만 돌려준다. 지금은 로그가 없다 |
| `org.springframework.security.oauth2.jwt.JwtException` 과 하위 클래스 | 위와 같다 | `KakaoIdTokenExchanger` 가 `FailureLogDetails` 로 원인 정보만 남기고 `AuthException` 으로 감싸 던진다 |

두 JWT 예외는 지금 스택으로
찍히는 경로가 없어서 목록에 넣어도 잃는 진단 정보가 없다. 원인 종류는 `exceptionType`(`ExpiredJwtException`,
`SignatureException` 등)으로 가른다. 메시지를 확인하면 필요에 따라 표에서 뺄 수 있다.

## 6. Loki 쿼리

스트림 선택자는 수집 설정(`lc-infra`)이 정한 라벨로 바꿔 쓴다. 아래 예의 `{app="lunchcatch"}` 는
자리표시다. `json` 뒤의 예시는 수집한 JSON 에 `message` 필드가 있고 그 값이 logfmt 모양이라는 전제다.

```logql
# 이벤트 하나 고르기
{app="lunchcatch"} |= "event=MEMBER_LOGIN_FAILED"

# 키로 거르기
{app="lunchcatch"} | json | line_format "{{.message}}" | logfmt | event="MEMBER_LOGIN_FAILED" | reason="SYSTEM_ERROR"

# 요청 한 건의 모든 로그
{app="lunchcatch"} | json | trace_id="a1b2c3d4e5f60718"

# 폴백이 일어난 곳 모아 보기
{app="lunchcatch"} | json | line_format "{{.message}}" | logfmt | fallback!=""

# 도메인 하나의 실패
{app="lunchcatch"} |~ "event=BILLING_[A-Z_]+_FAILED"
```
