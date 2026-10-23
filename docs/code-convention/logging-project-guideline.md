# 로깅 규칙: 런치캐치 백엔드

이 문서는 공통 로깅 규칙 4판(2026-10-21)을 이 저장소에 적용한 결과다. 이 저장소의 장치 이름,
설정값, 마스킹 키, 그리고 아직 규칙에 맞지 않는 코드의 목록을 갖는다. 이 저장소에만 해당하는 이유는
[logging-project-rationale.md](./logging-project-rationale.md) 가 갖는다.

| 문서 | 소유 |
|---|---|
| `logging-project-guideline.md` | 이 문서. 이 저장소의 장치, 설정, 마스킹 키, 이관 대상 |
| `logging-project-rationale.md` | 이 저장소에만 해당하는 근거 |

이 문서가 기대는 외부 자료의 출처와 확인 범위는 [logging-project-rationale.md](./logging-project-rationale.md) 의
17장이 갖는다. 이 문서에는 출처 목록을 복사하지 않는다.

`LOG-*` 번호는 공통 로깅 규칙 4판의 식별자를 유지한다. 공통 문서 3개는 이 저장소의 현재 이력에
없으므로 존재하는 복사본처럼 링크하지 않는다. 7장(이관 대상)에 있는 코드는 고쳐질 때까지 기존
모양을 허용하지만 새 코드는 이 문서의 규칙을 따른다.

---

## 1. 공통 장치

모든 장치는 `global/logging` 패키지에 있다. 업무 코드에서 이 장치들이 남기는 내용을 다시 쓰지
않는다(`LOG-1-01`).

| 층 | 클래스 | 하는 일 | 남기는 이벤트 |
|---|---|---|---|
| 요청 컨텍스트 | `MdcLoggingFilter` | 가장 먼저 실행된다. MDC 에 `traceId`, `method`, `uri`, `clientIp` 를 넣고 요청이 끝나면 비운다. 요청 헤더 `X-Trace-Id` 가 형식에 맞으면 이어받고, 응답 헤더로 돌려준다 | 없음 |
| 기술 로그 | `HttpBodyLoggingFilter` | 요청마다 접근 로그를 남기고 4xx, 5xx 는 가린 바디를 함께 남긴다. 바디 로깅에서 뺀 경로와 텍스트가 아닌 Content-Type 은 `shouldNotFilter` 가 필터 전체를 건너뛰어 접근 로그도 남지 않는다(7.2) | `HTTP_ACCESS` |
| 기술 로그 | `ExternalApiLoggingExchangeFilter` | `WebClientConfig` 의 `WebClientCustomizer` 가 모든 `WebClient.Builder` 에 붙인다. 쿼리 문자열을 뺀 경로만 남긴다. 지금은 실패할 때 스택과 `ex.toString()` 을 함께 남긴다. 콜백이 요청 스레드와 다른 스레드에서 실행되면 MDC 가 자동으로 전파되지 않아 `traceId` 가 빠진다(7.1) | `EXTERNAL_API_CALL`, `EXTERNAL_API_CALL_FAILED` |
| 기술 로그 | `TraceIdExchangeFilter` | 나가는 호출에 `X-Trace-Id` 헤더를 싣는다. 지금은 받는 쪽이 이 헤더를 쓰지 않아 효과가 없다 | 없음 |
| 기술 로그 | `SchedulerLoggingAspect` | `@Scheduled` 메서드마다 시작, 끝, 실패를 남긴다. 마지막 성공 시각을 게이지 `batch.job.last.success.timestamp`(태그 `scheduler`)로 내보낸다. 지금은 실패할 때 스택을 남기고 다시 던져 Spring 의 기본 오류 처리기가 스택을 한 번 더 남긴다(7.2). 고친 뒤에는 실행의 MDC 를 스케줄러의 `TaskDecorator` 가 넣고(실행의 시작점), 애스펙트는 `SCHEDULER_FAILED` 에 `exceptionType` 만 남기고 다시 던진다. 스택은 Spring 의 기본 오류 처리기가 한 번 남기고, 알림은 `SCHEDULER_FAILED` 에 건다 | `SCHEDULER_START`, `SCHEDULER_END`, `SCHEDULER_FAILED` |
| 기술 로그 | `AdminLoginService`·`AdminLoginAuditFilter`(`admin`) | 관리자 로그인 감사의 기록 대상과 처리 방식은 [관리자 API 설계의 로그인 절](../design/admin/admin-api-design.md#로그인)을 따른다 | `ADMIN_LOGIN_SUCCESS`, `ADMIN_LOGIN_FAILED`, `ADMIN_LOGIN_AUDIT_FAILED` |
| 이벤트 로그 | `GlobalExceptionHandler`(`global/exception`), `AuthExceptionHandler`(`auth/exception`) | 전역 예외 핸들러다 | 이벤트명 없음(7장) |

## 2. 업무 코드가 쓰는 도구

| 도구 | 쓰는 때 | 관련 규칙 |
|---|---|---|
| `TransactionLogging.afterCommit(Runnable)` | DB 상태 변경의 성공 로그를 커밋 뒤에 남긴다. 트랜잭션이 없으면 바로 실행한다 | `LOG-6-01` |
| `TransactionLogging.afterFailure(Runnable)` | 롤백됐거나 커밋 결과를 모르는 트랜잭션의 실패를 남긴다. 트랜잭션이 없으면 실행하지 않는다 | `LOG-6-01` |
| `ErrorCode.isExpectedTraffic()` | 정상 운영에서 대량으로 나오는 거절이면 `true` 를 돌려주게 에러 코드에 정의한다. `GlobalExceptionHandler` 가 이 값을 보고 `AccessLogSignal.markExpected()` 를 부른다 | `LOG-5-04` |
| `AccessLogSignal.markExpected(request)` | 위 경로를 거치지 않는 곳에서 직접 표시할 때 쓴다. 표시된 요청의 접근 로그는 4xx, 5xx 여도 DEBUG 로 내려간다 | `LOG-5-04`, `LOG-8-06` |
| `PiiMasker` | 개인정보를 가리는 유일한 유틸이다. `maskEmail`, `maskPhone`, `maskName`, `maskProviderId`(외부 계정 식별자), `maskGeneric`, `redact`(완전히 가림) | `LOG-7-03` |
| `RedisFailureClassifier.causeLabel(e)` | Redis 실패를 `TIMEOUT`, `OTHER` 로 분류한다. 값은 `reason` 키에 넣는다 | `LOG-4-01` |
| `FailureLogDetails.from(e)` | 예외 메시지 없이 원인 타입, HTTP 상태, SQLState, 발생 위치를 뽑는다. 지금은 객체째 `details=` 에 넣고 있어 `LOG-4-04` 에 맞지 않는다(7장) | `LOG-4-04` |
| `ClientIpResolver`(`global/http`) | 클라이언트 IP 를 정한다. `X-Forwarded-For` 의 마지막 항목을 쓰고 없거나 형식이 틀리면 `getRemoteAddr()` 로 돌아간다. `MdcLoggingFilter`, `AdminLoginAuditFilter`, `AuthRateLimitFilter` 가 같은 값을 쓴다. `getRemoteAddr()` 는 이 클래스 밖에서 부르지 않고 `ArchitectureTest` 가 막는다. `X-Forwarded-For` 를 읽는 코드는 리뷰에서 막는다. `server.forward-headers-strategy` 와 `server.tomcat.remoteip.internal-proxies` 는 함께 켜지 않는다. 이유는 [근거 문서 5장](./logging-project-rationale.md#5-clientip-를-검증하는-방식)에 있다 | `LOG-8-05` |
| `LogCapture`(테스트) | 대상 클래스의 로거에 붙어 남긴 로그를 모은다. 로그 테스트에 쓴다 | `LOG-8-09` |
| `AuditLogWriter`(`ops/contract`) | 관리자 행위와 돈이 오간 처리의 감사 기록을 DB 에 쓴다. 관리자 로그아웃, 계정 등록, 토큰 재발급, 포인트 정책, 환불, 포인트 조정, 결제 재조회, 재집계가 이미 쓴다 | `LOG-8-11` |

## 3. 공통 규칙의 예외

| 공통 규칙 | 이 저장소에서 | 이유 |
|---|---|---|
| `LOG-8-07` 의 "장치를 우회해 클라이언트를 직접 만드는 코드는 빌드에서 막는다" | 빌드 대신 리뷰에서 확인한다. `WebClient.builder()`, `WebClient.create()` 를 `global/config` 밖에서 부르는 코드는 리뷰에서 막는다 | [근거 문서 14장](./logging-project-rationale.md#14-webclient-직접-생성을-리뷰로-막는-이유) |

이 표에 없는 차이는 예외가 아니라 이관 대상이며 7장에 있다.

## 4. 설정값

`src/main/resources/logback-spring.xml` 이 갖는다.

| 프로필 | 항목 | 값 |
|---|---|---|
| `local` (기본 프로필) | 출력 | 콘솔 텍스트. 패턴에 MDC 값과 `%F:%line` 을 넣는다 |
| `local` | 루트 레벨 | DEBUG |
| `prod` | 인코더 | `LogstashEncoder`. MDC 키 `traceId`, `method`, `uri`, `clientIp` 를 필드로 넣고 `service=launchcatch` 를 더한다 |
| `prod` | 스택 | `ShortenedThrowableConverter`. 예외당 30 프레임, 최대 8192자, 원인 먼저, 프레임워크 패키지 일부 제외 |
| `prod` | appender | `AsyncAppender`. `queueSize=8192`, `discardingThreshold=0`, `neverBlock=true`, `includeCallerData=false`. `maxFlushTime` 은 지정하지 않아 기본 1초다(7.2) |
| `prod` | 운영 필드 | `service=launchcatch` 만 있다. 배포 버전과 인스턴스 필드는 없다(7.3) |
| `prod` | 루트 레벨 | INFO |

로거 레벨을 런타임에 바꾸는 수단(`LOG-5-07`)은 액추에이터의 `loggers` 엔드포인트다. 액추에이터는
`management.server.port=8081` 로 분리되어 있다. 지금 노출 목록은 `health, info, prometheus` 라서
`loggers` 가 빠져 있다(7장).

로그의 보존 기간과 조회 권한(`LOG-8-10`)은 [qa-observability-guideline.md](../software-quality/qa-observability-guideline.md) 의 `OBS-3-06` 이 정한다.

## 5. 바디 마스킹

`HttpBodyLoggingFilter` 가 갖는다. 공통 규칙의 기본 키(`LOG-7-08`) 외에 이 저장소가 더하거나
따로 정한 것만 적는다.

**민감 키를 판정하는 방법** 공통 모듈의 `SensitiveKeys` 가 판정한다. 키를 정규화한 뒤 제외,
접미사(`password`, `token`, `secret`, `address`), 정확히 같은 키의 순서로 본다. 민감 키의 값은 자료형과 관계없이
`***REDACTED***` 로 바꾸고, 파싱하지 못한 바디는 전체를 `***UNPARSEABLE_BODY_REDACTED***` 로 남긴다. 이 저장소는
아래 표의 키 가운데 모듈의 기본값에 없는 키를 `app.logging.sensitive-keys.extra` 에 넣고,
`nickname` 을 `app.logging.sensitive-keys.excluded` 에 넣는다.

**지금 코드는 아직 정규식이다** 위 방법은 7.1 의 `LOG-7-09` 항목을 고친 뒤의 기준이다. 고치기 전까지는
키 이름이 정확히 같을 때만 걸리므로 변형 이름을 하나씩 등록한다. 아래 표는 두 방식 모두의 키 목록이다.
접미사 규칙으로 걸리는 키(`newPassword`, `roadAddress` 등)는 모듈로 바꾼 뒤 설정에 넣지 않아도 된다.

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
| 바디를 남기는 Content-Type | `application/json`, `text/*`, `application/x-www-form-urlencoded`. 나머지는 필터를 건너뛴다 |
| 남기는 바디의 최대 길이 | 가린 뒤 2000자. 요청 바디는 8192바이트까지 캐싱한다 |

새 민감 필드를 요청이나 응답에 넣으면 접미사 규칙에 걸리는지 먼저 본다. 걸리지 않으면 위 표와 `extra` 설정에 키를 더한다.
정규식을 쓰는 동안에는 `SENSITIVE_JSON_FIELD` 와 `SENSITIVE_FORM_FIELD` 두 패턴에 같은 키를 더하고, 좌표처럼
숫자로 오는 값이면 `SENSITIVE_NUMERIC_COORDINATE` 에도 더한다. 빠뜨리면
[`HttpBodyLoggingFilterTest`](../../src/test/java/com/launchcatch/global/logging/HttpBodyLoggingFilterTest.java) 의 민감 필드 검사 테스트가 실패한다.

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

목록에 없는 외부 예외의 스택은 출력 단계의 메시지 마스킹(7.1)을 거쳐 남긴다. 두 JWT 예외는 지금 스택으로
찍히는 경로가 없어서 목록에 넣어도 잃는 진단 정보가 없다. 원인 종류는 `exceptionType`(`ExpiredJwtException`,
`SignatureException` 등)으로 가른다. 메시지를 확인하면 필요에 따라 표에서 뺄 수 있다.

## 6. Loki 쿼리

스트림 선택자는 수집 설정(`lc-infra`)이 정한 라벨로 바꿔 쓴다. 아래 예의 `{app="launchcatch"}` 는
자리표시다. `json` 뒤의 예시는 수집한 JSON 에 `message` 필드가 있고 그 값이 logfmt 모양이라는 전제다.

```logql
# 이벤트 하나 고르기
{app="launchcatch"} |= "event=MEMBER_LOGIN_FAILED"

# 키로 거르기
{app="launchcatch"} | json | line_format "{{.message}}" | logfmt | event="MEMBER_LOGIN_FAILED" | reason="SYSTEM_ERROR"

# 요청 한 건의 모든 로그
{app="launchcatch"} | json | traceId="a1b2c3d4e5f60718"

# 폴백이 일어난 곳 모아 보기
{app="launchcatch"} | json | line_format "{{.message}}" | logfmt | fallback!=""

# 도메인 하나의 실패
{app="launchcatch"} |~ "event=BILLING_[A-Z_]+_FAILED"
```

---

## 7. 이관 대상

공통 규칙에 맞지 않는 기존 코드다. develop `1eb4c27` 기준으로 조사했고, develop `1873a1f`(2026-10-21)에서
다시 확인했다. 그 사이에 바뀐 로그 코드는 `HttpBodyLoggingFilter` 의 DEBUG 가드와 `coupon` 의 새 로그뿐이라
기존 항목은 모두 유효하다. 새 로그에서 찾은 항목은 표에 더했다. 우선순위 1 은 잘못된
로그를 남기거나 민감한 값이 새는 버그다. 2 는 검색, 알림, 감사에 영향을 준다. 3 은 모양만 다르다.
해당 도메인을 고칠 때 같이 고치고, 고치면 이 표에서 지운다.

### 7.1 우선순위 1

| 규칙 | 위치 | 지금 | 고칠 모양 |
|---|---|---|---|
| `LOG-6-01` | `billing/service/RechargeReservationService` | `@Transactional` 안에서 `RECHARGE_RESERVE_DONE` 을 바로 남겨 롤백돼도 성공이 찍힌다 | `TransactionLogging.afterCommit` 으로 남긴다 |
| `LOG-7-06` | `GlobalExceptionHandler`(잘못된 파라미터, 잘못된 인자), `AuthExceptionHandler` | `detail={}` 에 `e.getMessage()` 를 넣는다 | 메시지를 빼고 `exceptionType`, `errorCode` 를 남긴다 |
| `LOG-7-06`, `LOG-8-07`, `LOG-6-07` | `ExternalApiLoggingExchangeFilter` | `EXTERNAL_API_CALL_FAILED` 의 `err={}` 에 `ex.toString()` 을 넣어 예외 메시지가 들어간다. 예외 객체도 함께 넘겨 스택을 남기는데, 예외는 호출자에게 전파되어 끝내는 곳에서 한 번 더 남는다. 콜백이 요청 스레드와 다른 스레드에서 실행되면 MDC 가 자동으로 전파되지 않아 `traceId` 가 빠진다 | `exceptionType` 만 남기고 스택은 넘기지 않는다. 호출을 시작할 때 MDC 를 복사해 두고, 콜백은 진입할 때의 MDC 를 보관한 뒤 복사본을 적용하고 `finally` 에서 보관한 MDC 로 되돌린다(`LOG-6-07`). 콜백이 요청 스레드에서 도는 경우에 요청의 MDC 가 지워지지 않는지도 테스트한다. 기존 테스트 `호출이_실패하면_WARN으로_남기고_스택트레이스도_함께_남긴다` 를 새 기준으로 바꾸고, 콜백 로그에 `traceId` 가 있는지 확인하는 테스트를 더한다 |
| `LOG-7-09` | `HttpBodyLoggingFilter` | 민감 키를 정규식으로 찾는다. 값 안에 이스케이프된 따옴표가 있으면(`"password":"ab\"cd"`) 따옴표 뒤가 남고, 좌표 외의 숫자 값(`"kakaoUserId":123`)은 가려지지 않는다 | JSON 은 파서로 읽어 키마다 값을 가린다. 자료형과 관계없이 가리고, 파싱에 실패하면 바디 전체를 가린다. form 은 디코딩한 뒤 키를 본다 |
| `LOG-7-13` | `logback-spring.xml` 의 `ShortenedThrowableConverter`, 예외를 끝내는 곳(`GlobalExceptionHandler`, 각 외부 클라이언트) | 스택에 찍히는 예외 메시지를 가리지 않는다. 5.1 의 예외도 스택과 함께 남을 수 있다. `WebClientResponseException` 의 스택이 남으면 외부 호출 로그에서 뺀 쿼리 문자열이 다시 나온다 | 기본 변환기는 메시지 마스킹을 지원하지 않는다. 5.1 의 예외는 끝내는 곳에서 원인 정보만 남긴다. 그 밖의 예외 메시지를 출력 단계에서 가릴지는 사용자 정의 변환기 구현 이슈에서 정하고, 정하기 전에는 지원하는 기능처럼 적지 않는다 |
| `LOG-7-06` | `CouponSequenceRecoveryService`(`COUPON_ISSUANCE_RECOVERY_FAILED`), `CouponSequenceService`(`COUPON_REDIS_BLOCK_FAILED`) | `reason={}` 에 `error.toString()` 을 넣어 예외 메시지가 들어간다 | `exceptionType` 을 쓰고, 사유는 `RedisFailureClassifier` 의 라벨로 `reason` 에 넣는다 |

### 7.2 우선순위 2

| 규칙 | 위치 | 지금 | 고칠 모양 |
|---|---|---|---|
| `LOG-6-05` | `OwnerLoginService`, `OwnerTokenRefreshService` | `stack=Arrays.toString(e.getStackTrace())` 로 스택을 문자열로 넣는다 | 예외를 마지막 인자로 넘기거나(`LOG-6-02` 에 따라 끝내는 곳일 때) `exceptionType` 만 남긴다 |
| `LOG-2-03` | `CampaignPauseResumeService`(2곳), `MemberProfileService`, `OwnerTokenRefreshService` | `event={}` 로 이벤트명을 인자로 넘긴다 | 리터럴로 쓴다 |
| `LOG-2-02`, `LOG-2-04` | `ops` 패키지(`LoggingBatchAlert`, `BatchJobRunner`, `BatchExecutionService`, `BatchExecutionRetryService`, `DailyJobScheduler`, `BatchServerId`, `ThreadSleepRetryBackoff`) | 이벤트명이 없는 한국어 문장이고 키가 한글이다(`이전소유자`, `사유`, `상태`, `시도`, `단계`, `멈춘단계`) | `event=BATCH_*` 와 영문 키로 쓴다. 배치 이어받기와 실패는 우선 고친다 |
| `LOG-2-02` | `GlobalExceptionHandler`, `AuthExceptionHandler`, `PosterImageInspector`, `FunnelEventRecorder`, `BudgetQueryServiceImpl`, `PointDeductorImpl` | 이벤트명이 없다 | 이벤트명을 붙인다 |
| `LOG-6-04`, `LOG-5-02` | `GlobalExceptionHandler` | 업무 예외를 WARN 이나 ERROR 로 남겨 업무 코드의 로그와 겹친다. 4xx 를 WARN 으로 남긴다 | 업무 예외는 DEBUG 로 남긴다. 처리되지 않은 예외만 스택과 함께 ERROR 로 남긴다 |
| `LOG-5-02`, `LOG-8-06` | `HttpBodyLoggingFilter` | 4xx 를 WARN 으로 남긴다 | INFO 로 남긴다. 바디는 그대로 남긴다 |
| `LOG-5-02` | `member/service` 의 `*_FAILED`(사유 `ALREADY_WITHDRAWN`, `EMPTY_UPDATE`, `INVALID_INPUT`, `ONBOARDING_ALREADY_COMPLETED`), `coupon` 의 `COUPON_*_FAILED`, `campaign` 의 `*_FAILED reason=OWNER_NOT_ACTIVE`, `OWNER_COUPON_*_FAILED` | 클라이언트 쪽 거절을 WARN 으로 남긴다 | INFO 로 남긴다 |
| `LOG-6-10` | `SpendReconciliationService`, `PgChargeReconciliationService`, `SettlementConsistencyStep`, `CampaignReportStep` | 배치 검사가 찾은 불일치를 WARN 으로만 남기거나(소진 대사, 정합성, 리포트) 아예 남기지 않는다(PG 대사) | 7.4 의 모양으로 바꾼다. 건마다 WARN, 실행 요약을 ERROR 로 남긴다 |
| `LOG-3-05` | `PgChargeReconciliationService` | PG 와 우리 금액이 다른 결제(`recordMismatch`)와 빠진 충전을 자동 복구한 결제(`recordMissingCharge`)를 기록 테이블과 메트릭에만 남기고 로그를 남기지 않는다 | `BILLING_PG_RECON_MISMATCH`(WARN)와 `BILLING_PG_RECON_CHARGE_RECOVERED`(WARN, `fallback=AUTO_RECOVER`)를 남긴다 |
| `LOG-5-07` | `application.yml` 의 `management.endpoints.web.exposure.include` | `loggers` 가 없어 런타임에 레벨을 바꿀 수 없다 | `loggers` 를 더한다. 8081 이 외부에 열려 있지 않은지 함께 확인한다 |
| `LOG-6-07` | `SchedulerLoggingAspect` | 스케줄러 실행에 `traceId` 가 없다 | 스케줄러의 `TaskDecorator` 가 실행마다 `traceId` 를 만들어 MDC 에 넣고 끝나면 되돌린다. 애스펙트는 MDC 를 다루지 않는다(1장) |
| `LOG-8-07` | `WebClientConfig` 주석 | `ArchitectureTest` 의 `WebClient는_직접_생성하지_않는다` 규칙이 빌드에서 막는다고 적지만 그 규칙이 없다 | 그 문장을 "직접 생성하지 않는다. 리뷰에서 확인한다" 로 바꾼다. 우회하면 무엇이 빠지는지 설명하는 앞 문장은 남긴다(3장) |
| `LOG-7-07` | 전역 | 사용자 입력 값의 CR, LF 를 지우는 유틸이 없다 | `PiiMasker` 옆에 유틸을 두고 사용자 입력을 값으로 넣는 곳에 쓴다 |
| `LOG-7-10` | 테스트 | 새 민감 필드가 마스킹 목록에 있는지 확인하는 테스트가 없다 | DTO 의 필드 이름이 민감해 보이는데 마스킹 판정에 걸리지 않으면 실패하는 테스트를 더한다 |
| `LOG-8-06`, `LOG-7-11` | `HttpBodyLoggingFilter` | 바디 제외 경로(`/v1/admin/auth/`, `/v1/admin/admins`, `/notifications/stream`)와 텍스트가 아닌 Content-Type(멀티파트 업로드 등)은 `shouldNotFilter` 가 필터 전체를 건너뛰어 `HTTP_ACCESS` 가 남지 않는다 | 필터는 모든 요청에 돌고, 바디를 캐싱하고 남길지만 경로와 Content-Type 으로 정한다 |
| `LOG-8-06` | `HttpBodyLoggingFilter` | `ContentCachingResponseWrapper` 에 상한이 없어 응답 전체를 메모리에 올린다 | 응답 캐싱에 상한을 두고, 상한을 넘거나 스트리밍인 응답은 캐싱하지 않는다 |
| `LOG-2-06` | `HttpBodyLoggingFilter` | `reqBody="{}" resBody="{}"` 에 넣는 바디의 큰따옴표와 역슬래시를 이스케이프하지 않아 logfmt 값이 첫 내부 따옴표에서 끝난다 | 감싸기 전에 `\` 와 `"` 를 이스케이프한다 |
| `LOG-8-08`, `LOG-6-02` | `SchedulerLoggingAspect` | `SCHEDULER_FAILED` 에 예외 객체를 넘겨 스택을 남기고 다시 던진다. Spring 의 기본 오류 처리기가 `Unexpected error occurred in scheduled task` 로 스택을 한 번 더 남긴다 | 애스펙트는 `exceptionType` 만 남기고 스택은 프레임워크 오류 처리기에 맡긴다 |
| `LOG-8-08` | 경보 설정(`lc-infra`) | 마지막 성공 시각 게이지는 작업이 처음 실행될 때 생긴다. 재기동 뒤 한 번도 돌지 않으면 시계열이 없어 낡은 값을 보는 알림이 울리지 않는다 | `absent()` 조건을 함께 둔다. 실제 선택자가 배치 서버 시계열만 고르는지는 `lc-infra` 에서 확인한다 |
| `LOG-6-07` | `CouponIssueBatcher`(`coupon-batch-*` 스레드), `FunnelEventRecorder`, `BatchExecutionRetryService` 의 실행기 | 직접 만든 스레드에서 남기는 로그에 MDC 가 없다 | 작업을 넘길 때 MDC 를 복사해 넘긴다. 받는 쪽은 진입할 때의 MDC 를 보관하고 복사본을 적용한 뒤 `finally` 에서 되돌린다(`LOG-6-07`) |
| `LOG-8-02` | `logback-spring.xml` | `maxFlushTime` 을 지정하지 않아 종료 때 1초 안에 내보내지 못한 로그가 버려진다 | 큐 크기 8192 와 graceful shutdown(30초)에 맞춰 값을 정한다 |

### 7.3 우선순위 3

| 규칙 | 위치 | 지금 | 고칠 모양 |
|---|---|---|---|
| `LOG-2-01`, `LOG-2-08` | `adserving`(`FeedRateLimitRepository`, `ServeFrequencyRepository`, `ServeRecordRepository`, `ImpressionCountRepository`, `ExclusionRepository`, `FeedService`, `ServeLogBuffer`), `AuthRateLimitFilter`, `AccessTokenCutoffVerifier`, `CampaignActivationRateLimiter`, `TemplateGenerationRateLimiter`, `OwnerStatusService`, `OwnerStatusCacheInvalidationService`, `OwnerLoginService` | 메시지 끝에 폴백 동작을 문장으로 붙인다(`통과시킨다`, `DB에서 점주 상태를 조회합니다.`) | `fallback=` 키로 바꾼다 |
| `LOG-2-05` | `BudgetQueryServiceImpl`, `PointDeductorImpl`(2곳), `GlobalExceptionHandler` | 쌍을 쉼표로 나눈다 | 공백으로 나눈다 |
| `LOG-2-07` | `PAYMENT_WEBHOOK_IGNORED` (6곳) | `reason=malformed` 처럼 소문자다 | `UPPER_SNAKE` 로 쓴다 |
| `LOG-2-09` | `DailyAggregationJob` | `DAILY_AGGREGATION_DONE outcomes={}` 에 `Map` 을 통째로 넣는다 | 결과별 개수를 키로 남긴다 |
| `LOG-3-03` | `DAILY_AGGREGATION_ERROR`, `PG_RECON_ERROR`, `PAYMENT_RECHECK_ERROR`, `KAKAO_UNLINK_RETRY_MARK_FAILED_ERROR`, `OWNER_REFRESH_REUSE_DETECTED`, `SCHEDULER_END`, `SETTLEMENT_CONSISTENCY_VIOLATION`, `PAYMENT_WEBHOOK_IGNORED` | 동의어 접미사를 쓴다 | `_FAILED`, `_SUSPECTED`, `_DONE`, `_MISMATCH`, `_SKIPPED` 로 바꾼다. `DAILY_AGGREGATION_ERROR` 와 `DAILY_AGGREGATION_FAILED` 는 `reason` 으로 나눈다 |
| `LOG-3-01` | `CANDIDATE_LOADED`, `SPEND_RECON_*`, `RESERVE_*`, `FUNDING_JUDGE_*`, `PG_RECON_*`, `HOURLY_TARGET_DONE`, `RATE_LIMIT_*` 등 | 도메인 접두사가 없다 | 도메인 이름을 앞에 붙인다 |
| `LOG-4-01`, `LOG-4-03` | `reason` 에 `getErrorCode().getCode()` 를 넣는 곳(`member/service` 등 약 14곳), `code=` 를 쓰는 곳(`campaign`, `coupon`, 예외 핸들러) | 에러 코드가 `reason` 과 `code` 에 흩어져 있다 | 우리 에러 코드는 `errorCode`, 토스의 코드는 `externalErrorCode` 로 쓴다 |
| `LOG-4-01` | `causeType`, `errorType`, `err`, `cause`(예외 클래스일 때) | 예외 클래스의 키가 여럿이다 | `exceptionType` 으로 쓴다. `RedisFailureClassifier` 의 라벨을 담은 `cause` 는 `reason` 으로 쓴다 |
| `LOG-4-04` | `FailureLogDetails` 를 `details=`, `cause=` 에 넣는 곳 | 객체를 통째로 넣는다 | `exceptionType`, `httpStatus`, `sqlState`, `sqlErrorCode`, `origin` 키로 펼친다 |
| `LOG-4-01` | `HTTP_ACCESS`, `EXTERNAL_API_CALL`, `TOSS_*` 의 `status`, `RATE_LIMIT_*` 의 `ip`, `id`, `subjectId` | 사전과 다른 키다 | `httpStatus`, `clientIp`(또는 MDC 에 맡기고 뺀다), `<엔티티>Id` 로 쓴다 |
| `LOG-4-01` | `campaigns=`, `rows=`, `visits=` 등 복수 명사로 개수를 담는 곳 | 개수의 키가 복수 명사다 | `<대상>Count` 로 쓴다 |
| `LOG-4-05` | `GlobalExceptionHandler` 의 `method`, `uri` | MDC 와 타임스탬프에 있는 값을 다시 쓴다 | 뺀다 |
| `LOG-4-01` | `MdcLoggingFilter`, `logback-spring.xml` | MDC 키가 `uri` 다 | `path` 로 바꾸고 Loki 쿼리를 함께 고친다 |
| `LOG-8-09` | `member` 외 도메인 | 로그 테스트가 일부 도메인에만 있다 | 보안, 돈, 정합성 이벤트부터 `LogCapture` 로 테스트를 더한다 |
| 없음 | `application.yml` | `management.tracing.sampling.probability` 가 있지만 추적 라이브러리 의존성이 없어 효과가 없다 | 설정을 지운다. 추적이 필요해지면 의존성과 추적 백엔드를 함께 들인다(근거 문서 4장) |
| `LOG-8-10` | `logback-spring.xml` | 운영 필드가 `service` 하나뿐이다 | 배포 버전과 인스턴스를 필드로 더한다. 값은 배포가 넣는 환경 변수(`APP_INSTANCE_ID` 등)에서 읽는다 |
| `LOG-7-12` | 전역 | 우리가 만드는 예외의 메시지에 사용자 입력이 들어가는지 점검한 적이 없다 | `new *Exception(` 의 메시지를 훑어 사용자 입력과 민감한 값을 뺀다 |
| 없음 | `logback-spring.xml` 의 로컬 패턴 주석 | `%X{traceId:-}` 문법이 Log4j2 전용이라고 적지만 Logback 도 지원한다 | 주석을 고친다. 기본값을 쓰지 않는 지금의 선택은 그대로 둬도 된다 |

### 7.4 레벨을 확정한 이벤트

member, billing, analytics 의 WARN 과 ERROR 를 코드로 확인해 레벨을 정했다. 정한 이유는
[근거 문서 13장](./logging-project-rationale.md#13-member-billing-analytics-의-레벨을-정한-근거) 에 있다. 이름을 바꾸는
이벤트는 7.3 의 이름 규칙(도메인 접두사, 접미사)을 함께 적용한 이름이다. 여기 없는 이벤트는 지금 레벨이 규칙에 맞는다.

| 도메인 | 이벤트(지금 이름) | 지금 | 확정 | 바꿀 이름과 키 |
|---|---|---|---|---|
| billing | `SPEND_RECON_MISMATCH`, `SPEND_RECON_OVERSPEND` | WARN | 건마다 WARN | `BILLING_SPEND_RECON_MISMATCH`, `BILLING_SPEND_RECON_OVERSPEND_MISMATCH` |
| billing | `SPEND_RECON_DONE` | INFO | 불일치가 1건 이상이면 ERROR, 아니면 INFO | `BILLING_SPEND_RECON_DONE campaignCount= mismatchCount=` |
| billing | (없음) PG 대사의 금액 불일치 | 로그 없음 | 건마다 WARN | `BILLING_PG_RECON_MISMATCH paymentId= expectedAmount= actualAmount=` |
| billing | `PG_RECON_DONE` | INFO | 불일치가 1건 이상이면 ERROR, 아니면 INFO | `BILLING_PG_RECON_DONE paymentCount= mismatchCount=` |
| billing | `PAYMENT_RECHECK_ERROR` | ERROR | WARN | `BILLING_PAYMENT_RECHECK_FAILED paymentId= exceptionType=` |
| billing | `PointDeductorImpl` 의 롤백 되돌리기 실패(이벤트명 없음) | ERROR | WARN | `BILLING_DEDUCT_UNDO_FAILED serveId= campaignId= fallback=LEAVE_FOR_RECON` |
| billing | `FUNDING_JUDGE_NO_PLAN` | WARN | INFO | `BILLING_FUNDING_JUDGE_SKIPPED campaignId= reason=NO_PLAN` |
| billing | `BILLING_DEDUCT_NO_PLAN` | WARN | DEBUG | 그대로 둔다. 건수는 `NO_PLAN_METRIC` 이 센다 |
| billing | `RECHARGE_RESERVE_FAILED`, `RECHARGE_RESERVE_MIRROR_FAILED` | ERROR | WARN | `BILLING_RECHARGE_RESERVE_FAILED fallback=NEXT_DAILY_RESERVE`, `BILLING_RECHARGE_RESERVE_MIRROR_FAILED fallback=NEXT_DAILY_RESERVE` |
| analytics | `SETTLEMENT_CONSISTENCY_VIOLATION` | WARN | 위반이 1건 이상이면 ERROR. 이 줄이 실행 요약이다 | `ANALYTICS_SETTLEMENT_CONSISTENCY_MISMATCH` |
| analytics | `CAMPAIGN_REPORT_VALID_IMPRESSION_MISMATCH` | WARN | 건마다 WARN, 실행 요약을 ERROR | `ANALYTICS_CAMPAIGN_REPORT_VALID_IMPRESSION_MISMATCH`, 요약은 `ANALYTICS_CAMPAIGN_REPORT_DONE campaignCount= mismatchCount=` |
| analytics | `DAILY_AGGREGATION_FAILED` | ERROR | 일시적 오류로 재시도를 다 쓴 경우 WARN, 일시적이지 않은 오류는 ERROR | `ANALYTICS_DAILY_AGGREGATION_FAILED reason=TRANSIENT_EXHAUSTED` 또는 `reason=SYSTEM_ERROR` |

레벨을 그대로 두는 것도 코드로 확인했다. 아래 이벤트는 레벨을 바꾸지 않는다. 이름과 키는 7.3 을 따른다.

| 도메인 | 그대로 두는 ERROR | 그대로 두는 WARN |
|---|---|---|
| member | `KAKAO_UNLINK_RETRY_EXHAUSTED`, `KAKAO_UNLINK_RETRY_REJECTED`, `KAKAO_UNLINK_RACED_REACTIVATION`, `KAKAO_UNLINK_RETRY_MARK_FAILED_ERROR`, `KAKAO_UNLINK_RETRY_CLEANUP_FAILED`, `KAKAO_UNLINK_SUCCESS_CLEANUP_FAILED`, `KAKAO_UNLINK_WEBHOOK_AMBIGUOUS`, `KAKAO_UNLINK_WEBHOOK_PROCESSING_FAILED`, `KAKAO_UNLINK_REJECTED`, `KAKAO_UNLINK_REJECTION_NOT_RECORDED`, `reason=SYSTEM_ERROR` 와 `TRANSACTION_COMPLETION_FAILED` 의 `MEMBER_*_FAILED` | `KAKAO_UNLINK_RETRY_FAILED`, `KAKAO_UNLINK_RETRY_SKIPPED`, `KAKAO_UNLINK_FAILED_QUEUED`, `KAKAO_LOGOUT_AFTER_COMMIT_FAILED`, `KAKAO_ID_TOKEN_VERIFICATION_FAILED`, `KAKAO_TOKEN_EXCHANGE_FAILED`, `KAKAO_UNLINK_WEBHOOK_REJECTED` |
| billing | `PAYMENT_APPROVAL_MISMATCH`, `PAYMENT_INQUIRY_MISMATCH`, `PAYMENT_RECHECK_EXHAUSTED`, `PG_RECON_ERROR`, `PG_RECON_INQUIRY_FAILED`, `RESERVE_NEGATIVE_BALANCE`, `RECHARGE_RESERVE_NEGATIVE_BALANCE`, `BUDGET_STATUS_HASH_EVICT_FAILED`, `BUDGET_STATUS_CONFIRM_EVICT_FAILED`, `TOSS_AUTH_FAILED` | `BUDGET_STATUS_MIRROR_FAILED`, `RESERVE_HASH_MIRROR_FAILED`, `SPEND_RECON_HASH_MISSING`, `BudgetQueryServiceImpl` 의 시간대 목표 없음, `PointDeductorImpl` 의 재판정 불일치, `RESERVE_STATUS_CAMPAIGN_MISSING`, `BILLING_RELEASE_MARK_BLOCKED`, `TOSS_*_UNKNOWN`, `TOSS_*_NOT_SENT`, `PAYMENT_PG_TID_ALREADY_USED`, `PAYMENT_WEBHOOK_IGNORED` |
| analytics | `DAILY_AGGREGATION_ERROR` | `DAILY_AGGREGATION_ATTEMPT_LOST`, `DAILY_AGGREGATION_LEASE_LOST`, `FunnelEventRecorder` 의 기록 실패 |
