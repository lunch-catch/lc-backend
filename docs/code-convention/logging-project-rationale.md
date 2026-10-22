# 로깅 규칙의 근거: 런치캐치 백엔드

이 문서는 [logging-project-guideline.md](./logging-project-guideline.md) 의 장치와 설정이 왜 지금
모양인지를 설명한다. 이 저장소에만 해당하는 사정과 외부 자료가 보장하는 범위를 적는다. 같은
내용이 코드 주석에도 있으면 코드 주석이 기준이고, 이 문서는 흩어진 주석을 한곳에서 찾게 해 준다.
코드 주석이 틀렸으면 이 문서가 바로잡은 내용을 적고, 주석을 고치는 일은 규칙 문서 7장에 둔다.
이 문서는 공통 규칙 4판(2026-10-21)을 기준으로 쓴다. `[R번호]` 는 17장의 외부 자료를 가리킨다.

---

## 1. 운영 appender 가 로그를 버리는 이유

공통 규칙 `LOG-8-02` 의 이 저장소 사정이다.

2026-08-30 부하 시험에서 선착순 쿠폰의 혼잡 응답 24,000건이 몰렸다. 로그 큐가 차자 요청 스레드가
큐에 자리가 날 때까지 기다렸다. 스레드가 묶이니 새 연결을 받지 못했고, 로드 밸런서가 연결 오류
11,590건과 504 응답 13,374건을 냈다. 혼잡이 5,134건이던 회차에는 연결 오류가 0건이었다.

그래서 `neverBlock=true` 로 맞바꿈을 뒤집었다. 로그를 잃지 않으려고 느려지던 것을, 느려지지 않으려고
로그를 버리는 쪽으로 바꿨다. Logback 은 이 설정에서 큐가 차면 이벤트를 버리고 호출자를 막지 않는다.
`discardingThreshold=0` 은 큐가 차기 전에 INFO 이하를 미리 버리지 않게 한다.[R1] `queueSize` 는
1024 에서 8192 로 올렸다. 버리는 것은 마지막 수단이고, 순간의 폭주는 큐가 흡수하는 편이 낫다는
프로젝트 판단이다. `coupon_issue_results_total` 을 등록하거나 증가시키는 코드는 현재 저장소에 없으므로
이 지표가 버린 로그를 센다고 보지 않는다.

종료할 때 큐를 비우는 시간(`maxFlushTime`)은 지정하지 않아 기본 1초다. 큐 8192건이 차 있는 상태에서
배포가 인스턴스를 내리면 이 시간 안에 내보내지 못한 로그는 버려진다.[R1] graceful shutdown 의
30초와 Logback 종료 순서가 같은 시간 예산을 공유한다고 보장할 근거는 없다. 실제 종료 순서를 측정한 뒤
값을 정한다(7.2). 이 사건과 같은 이유로 버려지면 안 되는 감사 기록은 로그가 아니라 `AuditLogWriter` 로
감사 저장소에 쓴다(공통 규칙 `LOG-8-11`).

## 2. 예상된 대량 거절을 DEBUG 로 내리는 장치

공통 규칙 `LOG-5-04` 와 `LOG-8-06` 의 이 저장소 구현이다.

선착순 쿠폰의 소진과 혼잡은 4xx, 5xx 응답이지만 정상 운영에서 나오도록 설계한 답이다. 재고가
1만인데 2만 명이 오면 절반이 소진을 받는다. 이 응답을 접근 로그 규칙대로 남기면 이벤트 한 번이
바디를 실은 만 줄을 남긴다. 1장의 사건이 바로 이 경로에서 났다.

접근 로그 필터는 상태 코드밖에 보지 못한다. 어느 실패가 예상된 것인지는 에러 코드가 안다. 그래서
에러 코드에 `isExpectedTraffic()` 를 두고, 예외를 처리하는 쪽이 그 값을 보고
`AccessLogSignal.markExpected()` 로 요청 속성에 표시를 남긴다. 요청 속성을 쓰는 이유는 필터가 예외
처리보다 바깥에서 나중에 돌기 때문이다. 필터의 `finally` 가 돌 때는 표시가 이미 남아 있다.

## 3. 바디 마스킹 키를 고른 이유

공통 규칙 `LOG-7-08` 에 이 저장소가 더한 키의 이유다.

| 키 | 더한 이유 |
|---|---|
| `authorizationCode`, `state`, `nonce` | 카카오 로그인 실패는 흔한 4xx 경로다. 이 키가 빠지면 4xx 바디 로그에 카카오 인가 코드가 상시 평문으로 남는다 |
| `sub` | 카카오 회원 식별자다. 인증 정책이 아니라 개인정보 정책이 평문 보관을 막는다 |
| `lat`, `lng`, `latitude`, `longitude` | 노출 로그와 접속 기록에는 좌표를 소수점 3자리로 반올림한 값만 남기기로 했다. 요청 바디가 그대로 찍히면 그 정책이 로그 쪽에서 뚫린다. 숫자로 오는 좌표와 캐시 끝에서 잘린 숫자도 따로 잡는다 |
| `roadAddress`, `locationNickname` | 회원이 저장한 위치는 집이나 회사 같은 실제 장소라 좌표와 같은 급의 개인정보다. 점주가 가게를 등록할 때 보내는 `roadAddress` 도 함께 가려지지만, 가게 주소를 로그에서 못 보는 불편이 회원 위치가 새는 위험보다 작다 |
| `initialPassword`, `currentPassword`, `newPassword` | 키 이름이 `password` 와 정확히 달라서 따로 적어야 걸린다 |
| `representativeName`, `fid` | 대표자 실명과 기기 식별자는 개인정보다 |
| `providerUserId`, `kakaoUserId`, `user_id`, `paymentKey` | 값이 새면 계정이나 결제 건을 특정할 수 있다 |
| `businessRegistrationNumber`, `businessNumber` | 증빙 서류와 같은 급으로 다룬다 |

`nickname` 은 회원 계정과 연결되므로 공개 표시용 값이어도 개인정보가 될 수 있다.[R14] 지금은 서비스
화면에 공개되는 값이고 장애 분석에 필요하다는 프로젝트 판단으로 마스킹 예외에 두었다. 이 예외를
유지할지는 개인정보 노출 위험과 진단 필요성을 함께 검토해 정한다.

form-urlencoded 바디를 따로 잡는 이유는 JSON 패턴이 `password=1234&token=…` 모양에 걸리지 않기
때문이다(OBS-3-04, SEC-4-02). 바디를 남기는 Content-Type 을 텍스트 계열로 제한한 이유는 이미지
업로드 같은 바이너리를 UTF-8 로 억지로 디코딩해 깨진 문자를 남기고, 남기지 않는 정상 응답에서도
매번 캐싱하는 낭비를 막기 위해서다(OBS-3-05). 요청 바디의 캐시 한도(8192바이트)를 로그 길이 한도
(2000자)보다 넉넉히 잡은 이유는 한글이 UTF-8 에서 글자당 최대 3바이트라서다. 바이트 한도를 글자 수와
같게 잡으면 남기기도 전에 글자가 중간에 잘린다.

## 4. traceId 가 정식 분산 추적이 아닌 이유

`MdcLoggingFilter` 의 `traceId` 는 요청을 받을 때 만든 문자열 하나를 MDC 에 넣는 간이 구현이다.
OpenTelemetry 같은 분산 추적이 아니라서 span 의 부모 자식 관계도, HTTP, DB, Redis 호출의 자동
계측도 없다. 이 서비스는 모놀리스 하나이고 서비스 경계를 넘는 호출은 카카오와 토스뿐이다. 두
외부 서비스는 우리 헤더를 이어받지 않는다. 그래서 지금 `traceId` 는 한 프로세스 안에서 요청 한 건의
로그를 묶는 데 쓰인다. 그것만으로도 접근 로그와 이벤트 로그를 잇고, 업무 식별자가 정해지기 전의
로그까지 묶는다.

`TraceIdExchangeFilter` 는 나가는 호출에 `X-Trace-Id` 를 싣지만 지금은 받는 쪽이 없어 효과가 없다.
우리가 만든 다른 서비스를 호출하게 될 때 바로 쓰려고 미리 둔 것이고, 비용은 거의 없다. 여러 서비스에
걸친 지연 분석이 필요해지면 이 필터를 걷어 내고 OpenTelemetry 로 옮긴다.

MDC 는 스레드에 붙지만 Reactor `Context` 는 구독에 붙는다. Reactor 는 실행 스레드를 고정하지 않으므로
스케줄러를 바꾸면 MDC 가 자동으로 따라가지 않는다.[R4][R5] 1판은 "외부 클라이언트를 `.block()` 으로
동기 호출하는 동안은 스레드가 바뀌지 않아 문제가 없다" 고 적었는데 틀린 설명이다. `.block()` 은 호출한
스레드가 결과를 기다릴 뿐이다. `ExternalApiLoggingExchangeFilterTest` 로 확인한 결과 `doOnNext` 와
`doOnError` 는 응답 Publisher 가 신호를 보낸 스레드에서 실행됐다. 요청 스레드에서 신호를 보내면 MDC 가
남았고 다른 스레드에서 보내면 `traceId` 가 빠졌다. 실제 WebClient 요청의 콜백이 항상 Reactor Netty
이벤트 루프에서 돈다고 단정하지 않는다. 이 저장소에는 Reactor 컨텍스트와 MDC 를 잇는 장치가 없다.
고치는 방법은 이관 대상 7.1 에 있다.

`application.yml` 에 `management.tracing.sampling.probability` 가 있지만 추적 라이브러리 의존성이
없어서 아무 효과가 없다. 이 설정은 이전 프로젝트(fm-backend)에서 함께 옮겨 왔다. fm-backend 에서는
"장애 주입 실험 직전에 100% 로 올릴 수 있어야 한다" 는 의도로 넣었지만, 그 저장소의 히스토리 전체에
추적 의존성이 들어온 적이 없어 그곳에서도 동작한 적이 없다. 남겨 두면 추적이 켜져 있다고 오해하게
만들므로 지운다(7.3). 추적이 필요해지면 `micrometer-tracing` 브리지와 추적 백엔드를 함께 들인다.
추적 브리지와 로그 상관관계를 구성하면 Micrometer Tracing 이 현재 span 의 `traceId` 와 `spanId` 를
MDC 에 연결할 수 있다.[R15] 그때 `MdcLoggingFilter` 와 `TraceIdExchangeFilter` 의 역할을 함께 정리한다.

## 5. clientIp 를 검증하는 방식

`X-Trace-Id` 와 `X-Forwarded-For` 는 클라이언트가 마음대로 보낼 수 있는 헤더다. 그래서 허용 문자와
길이를 검사한다. 목적은 정확한 IP 형식 검사가 아니라 로그 인젝션(개행, 제어 문자)과 비정상적으로 긴
값을 막는 것이다(SEC-3-01).

형식 검사로는 위조를 막지 못한다. 로드 밸런서는 실제 접속 IP 를 `X-Forwarded-For` 의 마지막 항목에
덧붙인다. 앞쪽 항목은 클라이언트가 보낸 값이다. 그래서 `MdcLoggingFilter`, `AdminLoginAuditFilter`,
`AuthRateLimitFilter` 가 `ClientIpResolver` 하나를 공유하고, 이 해석기가 마지막 항목을 쓴다. 헤더가 없거나
마지막 항목의 형식이 틀리면 `getRemoteAddr()` 로 돌아간다.

### 공유 해석기를 기준으로 삼는 이유

Tomcat 의 Remote IP Valve 에 맡기는 방법도 있다. 이 Valve 는 신뢰하거나 내부 프록시로 설정된 프록시가
`remoteIpHeader` 에 남긴 항목을 믿는다.[R8] 지금은 해석기를 기준으로 삼는다. 이유는 넷이다.

1. **현재 구성에서 두 방법이 같은 값을 낸다.** ALB 는 TCP 피어라 자기 IP 를 헤더에 넣지 않고 받은 헤더 끝에
   실제 접속 IP 를 덧붙인다. 맨 오른쪽 항목이 공인 IP 라 Valve 가 걷어 낼 항목이 없고, 그 값이 그대로
   `getRemoteAddr()` 가 된다. 이 설명은 Tomcat 이 오른쪽부터 처리한다는 구현에 기대고 있어서, 공식 문서가
   보장하는 사실이 아니다.[R8]
2. **Tomcat 방식은 저장소 밖 확인이 선행 조건이다.** 실제 프록시 대역과 첫 프록시가 전달 헤더를 지우는지가
   확인되지 않았다(lunch-catch/lc-infra#25). 해석기는 그 확인 없이 지금 맞는 값을 낸다.
3. **`internalProxies` 기본값은 사설 대역 전체를 프록시로 본다.**[R8] VPC 안에서 ALB 를 거쳐 들어오는
   클라이언트의 IP 는 사설이라 함께 걷혀 나가고, 남는 항목이 없으면 ALB 의 IP 가 `getRemoteAddr()` 가
   된다. 그러면 시도 횟수 제한이 그 트래픽 전체를 한 바구니로 묶는다. 해석기는 마지막 항목을 그대로 쓰므로
   사설이든 공인이든 구분이 남는다.
4. **비용 차이가 크다.** 해석기는 구현과 테스트가 끝나 있다. Tomcat 방식은 설정 변경, 프록시 대역 합의,
   세 필터 되돌리기가 필요한데 1번에서 보듯 얻는 값은 같다.

### 두 방법을 함께 켜지 않는다

`server.forward-headers-strategy` 와 `server.tomcat.remoteip.internal-proxies` 를 켜면서 해석기도 쓰면
위조한 IP 가 들어온다. Valve 는 신뢰 대역에 해당하는 항목을 `X-Forwarded-For` 에서 걷어 내 `getRemoteAddr()`
로 옮기고 남은 항목만 헤더에 다시 쓴다. 남는 것은 클라이언트가 보낸 항목이다. 그 뒤에 마지막 항목을 읽는
해석기가 돌면 위조한 값을 클라이언트 IP 로 돌려준다. 이 조합은 #722 가 막은 구멍을 다시 연다. 지금은
`forward-headers-strategy` 를 설정하지 않아 문제가 없다.

### Tomcat 방식으로 넘어가는 조건

틀렸다는 뜻이 아니라 지금 쓰지 않는다는 뜻이다. 아래 가운데 하나가 생기면 넘어간다. 그때는 해석기를
걷어 내고 Valve 설정으로 바꾼다.

- **ALB 앞에 프록시가 하나 더 붙는다.** 마지막 항목이 그 프록시의 IP 를 가리키므로, 신뢰 대역으로
  판단하는 Tomcat 방식이 맞다.
- **`X-Forwarded-Proto` 나 `X-Forwarded-Host` 가 필요해진다.** 쿠키의 `Secure` 판정이나 리다이렉트 URL
  생성이 그렇다. Valve 는 그것까지 함께 처리하고 해석기는 IP 만 본다.
- **클라이언트 IP 를 보는 코드가 세 필터 밖으로 늘어난다.** Valve 는 `getRemoteAddr()` 하나로 전부 맞춘다.
  해석기 아래에서 새 코드가 `getRemoteAddr()` 를 그냥 부르면 ALB 의 IP 를 받는다.

`getRemoteAddr()` 호출은 `ArchitectureTest` 가 `ClientIpResolver` 밖에서 막는다. `X-Forwarded-For` 를
`getHeader` 로 읽는 코드는 문자열 인자라 ArchUnit 이 못 잡아서 리뷰에서 막는다.

## 6. 외부 호출 로그가 경로만 남기는 이유

카카오처럼 인가 코드와 액세스 토큰을 쿼리 문자열로 주고받는 외부 API 가 있다. URL 을 그대로 남기면
그 값이 샌다(SEC-4-02). 바디를 남기지 않는 이유는 `WebClient` 의 바디가 Publisher 라 가로채려면 따로
감싸야 하고 복잡도가 커지기 때문이다. 이 로그의 목적은 외부 API 가 느려서 우리가 멈췄는지를 증명하는
것이고, 상태 코드와 소요 시간이면 충분하다.

## 7. 스케줄러 게이지의 모양

마지막 성공 시각 게이지는 작업이 처음 실행된 시각으로 초기화한다. 계속 실패하는 작업도 잡기
위해서다. 성공할 때만 값이 갱신되므로 실패만 반복하면 값이 그대로 낡아 알람이 울린다. 0 으로 두면
기동 직후부터 낡은 값으로 보여 첫 실행 전에 알람이 울린다. 대신 이 방식에는 빈틈이 둘 있다. 게이지는
첫 실행에서 생기므로 재기동 뒤 작업이 한 번도 돌지 않으면 시계열이 없다. 재기동할 때마다 값이 그 시각으로
다시 시작하므로, 재기동이 잦으면 계속 실패하는 작업도 낡아 보이지 않는다. 앞의 것은 시계열이 없을 때
결과를 만드는 `absent()` 조건으로 막는다.[R9] 뒤의 것은 실패 이벤트(`SCHEDULER_FAILED`)의 건수 알림이
함께 잡는다. 실제 경보 선택자가 배치 서버만 고르는지는 이번 PR 에서 확인하지 않았다.

태그 이름은 `job` 이 아니라 `scheduler` 다. Prometheus 가 대상을 스크랩할 때 `job` 라벨을 자동으로
붙이고 그 값은 설정한 스크랩 잡 이름이다.[R10] 애플리케이션이 같은 이름의 태그를 내보내면
`honor_labels` 가 기본값인 `false` 일 때 애플리케이션의 라벨은 `exported_job` 으로 바뀐다.[R11]
이 경우 `job` 을 기준으로 작성한 알람은 스케줄러 이름이 아니라 스크랩 잡 이름을 본다. `lc-infra` 의
실제 `honor_labels` 값은 이번 PR 에서 확인하지 않았다.

## 8. 로컬 설정의 모양

`%F` 는 호출 위치 정보를 계산하므로 빠른 변환자가 아니다.[R2] 로컬은 트래픽이 적고 동기 로깅이라 부담이
작다. IntelliJ 콘솔은 `ClassName.java:42` 모양을 클릭하면 그 줄로 가는 링크로 만든다. 그래서 로컬에서만
켠다. `%logger{36}` 바로 뒤에 괄호로 시작하는 글자를 붙이면 Logback 의 패턴 파서가 그 괄호를 다른
변환자의 시작으로 읽는다. Logback 문서도 리터럴 괄호를 이스케이프하도록 정한다.[R2]
`LogbackPatternTest` 는 이스케이프하지 않으면 뒤의 메시지가 출력되지 않고 이스케이프하면 출력되는 것을
현재 Logback 버전에서 확인한다. 지금 패턴은 괄호 없이 쓴다.

`logback-spring.xml` 주석은 `%X{traceId:-}` 같은 기본값 문법이 Log4j2 전용이라 Logback 이 지원하지 않는다고
적는다. 1판도 이 설명을 옮겨 적었는데 틀렸다. Logback 의 `%X{key:-default}` 는 값이 없을 때 기본값을
출력한다.[R2] `LogbackPatternTest` 도 같은 동작을 확인한다. 지금 패턴은 기본값 없이 `%X{traceId}` 를
써서 값이 없으면 빈 문자열이 찍힌다. 이 선택은 그대로 두고 주석만 고친다(7.3).

로컬의 루트 레벨이 DEBUG 인 이유는 접근 로그 필터가 정상 응답의 바디를 DEBUG 에서만 남기기 때문이다.
서드파티까지 DEBUG 라 기동 로그가 길다. 줄이려면 시끄러운 로거만 따로 올린다.

## 9. 운영 스택 설정의 값

스택이 길게 쏟아지는 것을 막으려고 예외당 30 프레임, 최대 8192자로 자르고 클래스 이름을 36자로
줄인다. 원인을 먼저 보이게 하고 설정한 정규식과 연속해서 일치하는 프레임을 줄인다.[R3] 이 변환기는
예외 메시지를 그대로 출력하고 메시지 마스킹 기능을 제공하지 않는다.[R3] 현재 제외한 패키지가 진단에
필요한 프레임까지 숨기는지는 실제 스택을 보고 조정한다.

## 10. 관리자 로그인 감사 필터를 따로 둔 이유

관리자 로그인 요청의 바디에는 비밀번호가 있어서 바디 로깅에서 통째로 뺐다(공통 규칙 `LOG-7-11`).
그런데 `HttpBodyLoggingFilter` 는 바디를 뺀 경로에서 필터 전체를 건너뛰므로 접근 로그도 남지 않는다
(이관 대상 7.2). 감사 필터는 그 빈자리를 메운다. 바디와 쿠키를 읽지 않고 시각, IP, 성공 여부만 남긴다.
보안 필터 체인보다 먼저 실행해서 입력 오류나 시도 횟수 제한으로 끝나는 요청도 기록한다.

다만 이 기록은 애플리케이션 로그라서 큐가 차면 버려질 수 있다(1장). 관리자 로그아웃, 계정 등록, 토큰
재발급은 이미 `AuditLogWriter` 로 감사 저장소에 쓰는데 로그인 시도만 빠져 있다. 공통 규칙 `LOG-8-11` 에
따르면 감사 저장소에도 남기는 것이 맞다. 다만 모든 시도를 쓰면 양이 많고 무차별 대입 공격 때 저장소에
부담을 준다. 또 `audit_log.admin_id` 가 NOT NULL 이라 없는 계정의 실패는 지금 테이블에 넣을 수 없다.

그래서 성공과 존재하는 계정의 실패만 감사 저장소에 쓰기로 했다. 특정 계정을 노린 시도는 보안상 의미가 있다.
없는 계정과 제한에 걸려 거절된 요청은 애플리케이션 로그와 메트릭으로 센다. 실패 결과와 `adminId` 를 아는 곳은
로그인 서비스라서 기록 위치를 필터에서 서비스로 옮긴다. 필터에서는 실패 사유를 가를 수 없다.

시도 횟수 제한(`AuthRateLimitFilter`)은 IP 별 1분 10회이고 계정별 제한은 없다. IP 를 바꿔 가며 한 계정을 노리는
분산 시도에는 쓰기량의 상한이 되지 못한다. 그래서 계정별 실패 건수를 메트릭으로 세어 알림을 건다. 계정별 잠금은
정상 관리자의 잠금과 해제 절차까지 설계해야 하는 보안 기능이라 로깅 범위 밖으로 두고 별도 이슈로 다룬다. 관리자
계정 수가 적어 쓰기량 자체는 크지 않다고 보았다.

`AdminLoginService.login` 은 `@Transactional(NEVER)` 이고 `AuditLogWriterImpl` 은 `MANDATORY` 라서 호출하는 쪽에
트랜잭션이 있어야 한다. 실패 기록이 예외와 함께 롤백되지 않도록, 기록은 별도 트랜잭션(`AdminLoginTransactionService`
처럼)에서 커밋한 뒤 예외를 던진다. IP 는 `audit_log` 에 칸이 없어 `detail` 에 `clientIp=…` 로 담는다. 조회가 잦아지면
칸을 추가한다.

이 변경은 admin 서비스 코드를 고치는 일이라 로깅 이슈가 아니다. admin 담당자와 합의해 별도 이슈로 처리하고, 합의 전에는
지금처럼 애플리케이션 로그로만 남는다.

「개인정보의 안전성 확보조치 기준」 제8조는 개인정보취급자가 개인정보처리시스템에 접속해 처리한 업무 내역을
접속기록으로 관리하도록 한다. 일반적인 보존 기간은 1년이고 정보주체 수, 처리 정보, 사업자 조건 가운데 고시의
조건에 해당하면 2년이다. 접속기록이 위·변조와 도난, 분실되지 않게 보관해야 한다.[R13] 관리자 로그인 기록은
접속기록의 일부가 될 수 있지만 로그인 성공과 실패만으로 처리한 정보주체와 업무 내역까지 기록하지는 못한다.
따라서 로그인 기록만으로 제8조의 접속기록 요건을 충족한다고 단정하지 않는다. 인증 성공과 실패를 보안 이벤트로
기록하는 근거는 OWASP 로깅 지침이 더 직접적이다.[R16] 구체적인 법률 적용 범위와 보존 기간은 별도로 확인한다.
애플리케이션 로그는 보존 기간이 수집 설정을 따르고 큐가 차면 버려져 법률상 보존을 보장하지 못한다.

## 11. 액추에이터를 8081 로 나눈 이유

8080 에는 `/actuator` 경로가 아예 없어서 바깥에서 막는 규칙이 필요 없다. 이관 대상 7.2 에서 `loggers`
엔드포인트를 노출하더라도 8081 이 외부에 열려 있지 않으면 바깥에서 로그 레벨을 바꿀 수 없다.

## 12. 이관을 한 번에 하지 않는 이유

이관 대상 7장은 로그 호출 254곳 가운데 상당수를 건드린다. 한 번에 바꾸면 리뷰가 어렵고, Loki 에 이미
걸린 쿼리와 알림이 한꺼번에 깨진다. 그래서 우선순위를 나눴다. 우선순위 1 은 사실과 다른 로그를
남기거나 민감한 값을 흘리는 버그라 먼저 고친다. 우선순위 2 는 검색, 알림, 보안에 영향을 준다.
우선순위 3 은 모양만 다르므로 그 도메인을 고칠 때 같이 고친다. 이벤트명이나 키를 바꿀 때는 그
이벤트에 걸린 쿼리와 알림을 같은 PR 에서 고친다.

롤링 배포 중에는 옛 버전과 새 버전의 인스턴스가 함께 돈다. 그동안은 옛 이름과 새 이름이 함께 찍히므로
쿼리와 알림이 둘 다 잡게 한다(`|~ "event=(OLD_NAME|NEW_NAME)"`). 배포가 끝나고 옛 이름이 더 찍히지 않으면
옛 이름을 지운다. 대시보드의 과거 구간은 옛 이름으로만 남아 있으므로, 과거와 견주는 패널은 옛 이름을
보존 기간 동안 남겨 둔다.

## 13. member, billing, analytics 의 레벨을 정한 근거

규칙 문서 7.4 의 레벨은 공통 규칙 `LOG-5-08` 에 따라 세 도메인의 담당자와 합의해 정했다(2026-10-21).
판단은 하나의 질문으로 했다. 이 로그를 본 사람이 무엇을 해야 하는가, 그리고 하지 않으면 저절로 고쳐지는가.

**대사와 정합성 검사의 불일치** 소진 대사와 PG 대사가 찾은 불일치는 `settlement_mismatch` 에 미해결로
쌓인다. 관리자가 `/v1/admin/settlement-mismatches` 에서 보고 `OVERCHARGE_REVERSAL` 같은 처리로 닫는다.
사람이 처리하는 문제지만 00:00 배치라 당장 처리할 필요는 없다. 그래서 공통 규칙 `LOG-6-10` 의 모양을
따른다. 건마다 WARN 으로 남기고, 실행 요약(`*_DONE`)이 불일치를 하나라도 찾으면 ERROR 가 되어 알림을
한 번 울린다. 정합성 검사(`SETTLEMENT_CONSISTENCY_VIOLATION`)는 검사마다 위반 수만 남기는 한 줄이라 그
줄을 실행 요약으로 본다. 검사는 고치지 않고 알리기만 하므로 위반은 사람이 원인을 찾아야 한다.

**캠페인 리포트의 유효 노출 불일치** 광고 서빙은 노출을 저장하는 트랜잭션 안에서 정산의 판정을 부른다.
`PointDeductor` 계약이 `Propagation.MANDATORY` 라서 `impression_log` 의 유효 노출과 `deduction_result` 의 판정
행은 함께 커밋되고 함께 롤백된다. 두 행은 같은 받은 날을 쓰고, serveId 하나에 유효 노출은 하나뿐이다.
그래서 구조상 두 수는 어긋날 수 없고, 어긋나면 버그나 수동 데이터 수정의 신호다. 보존 기간 경계에서
재집계할 때 노출 쪽만 지워진 경우도 있지만 드물다. 사람이 원인을 찾아야 하므로 대사와 같은 모양으로 둔다.

**충전 뒤 재예약의 누락** `RECHARGE_RESERVE_FAILED` 와 `RECHARGE_RESERVE_MIRROR_FAILED` 는 충전한 점주의
광고가 그날 일찍 멈추는 쪽의 오차다. 다음 00:00 예약이 늘어난 잔액을 반영하므로 다음 날 저절로 고쳐진다.
코드에는 그날 안에 수동으로 다시 예약하는 경로가 없어서 사람이 할 일이 없다. 그래서 WARN 으로 내린다.
점주 문의 때문에 당일 복구가 필요해지면 수동 재예약 경로를 만들고 그때 ERROR 로 올린다.

**자동으로 다시 도는 실패** `PAYMENT_RECHECK_ERROR` 는 선점이 만료되면 다시 대상이 되고, 계속 실패하면
`PAYMENT_RECHECK_EXHAUSTED` 가 ERROR 로 잡는다. 그래서 WARN 이다. `DAILY_AGGREGATION_FAILED` 는 실패한 날짜가
미처리로 남아 다음 실행이 다시 집계한다. 일시적 오류는 그것으로 풀리므로 WARN 이다. 일시적이지 않은 오류는
다음 날에도 똑같이 실패하므로 ERROR 다. 코드가 `TransientFailures.isTransient` 로 둘을 이미 나눈다.

**대사가 나중에 잡는 오차** `PointDeductorImpl` 이 롤백된 노출의 카운터를 되돌리지 못하면 소진이 원장보다
큰 채로 남는다. 과금이 덜 되는 안전한 쪽이고, 다음 날 소진 대사가 불일치로 기록한다. 사람이 볼 일은 대사의
이벤트가 맡으므로 이 줄은 WARN 이다.

**설계된 경우** `FUNDING_JUDGE_NO_PLAN` 은 예약 단계 뒤에 서빙 대상이 된 캠페인이고, `BILLING_DEDUCT_NO_PLAN` 은
전날 집행이 끝난 캠페인의 노출이다. 둘 다 계약에 적힌 정상 경로라 문제가 아니다. 앞의 것은 배치에서 캠페인마다
한 번이라 INFO 다. 뒤의 것은 노출마다 찍히고 메트릭이 이미 세므로 DEBUG 다.

**그대로 둔 ERROR** 금액 불일치(`PAYMENT_*_MISMATCH`)는 결제를 확인 중으로 돌리지만 재조회해도 같은
불일치가 나와 결국 소진까지 간다. PG 대사의 조회 실패와 처리 실패는 그 영업일을 다시 대사하지 않는다. 잔액
음수는 원장이 어긋난 신호다. 해시 삭제 실패는 낡은 중단 표시로 중단된 캠페인에 과금될 수 있고 그날 안에
고쳐지지 않는다. 카카오 연결 끊기의 소진, 영구 거절, 재가입 경합, 기록 실패, 정리 실패, 웹훅 처리 실패는
자동으로 다시 돌지 않는다. 웹훅은 카카오에 200 으로 답하므로 카카오도 다시 보내지 않는다. 모두 사람이 손대야
끝난다.

## 14. WebClient 직접 생성을 리뷰로 막는 이유

`WebClientConfig` 의 `WebClientCustomizer` 는 주입받은 `WebClient.Builder` 에 타임아웃, 커넥션 풀, 외부 호출
로깅을 붙인다. `WebClient.builder()` 를 직접 부르면 이 셋이 조용히 빠진다. 상대가 응답을 주지 않으면 그 요청
스레드가 무기한 묶이고, 호출 로그도 남지 않는다.

이전 프로젝트(fm-backend)는 이 우회를 `ArchitectureTest` 의 `WebClient는_직접_생성하지_않는다` 규칙으로 빌드에서
막았다. 이 저장소로 옮겨 오면서 그 규칙을 설명하는 주석과 이름은 따라왔지만 규칙 자체는 빠졌다. 지금은 규칙을
더할 여유가 없어 리뷰에서 확인한다. 지금 `src/main` 에 직접 생성하는 코드는 없다. 규칙을 더할 때는 fm-backend 의
규칙에서 패키지를 `..config..` 에서 `..global.config..` 로 바꿔 가져오면 된다.

## 15. 외부 예외 메시지를 가리는 대상을 고른 이유

공통 규칙 `LOG-7-13` 의 대상 목록(규칙 문서 5.1)을 고른 이유다.

이 저장소의 외부 호출은 카카오와 토스뿐이고 둘 다 `WebClient` 를 쓴다. Spring 의 `WebClientResponseException`
은 4xx, 5xx 응답을 예외로 바꾸면서 메시지에 메서드와 전체 URI 를 담는다. 카카오는 인가 코드와 토큰을 쿼리
문자열로 주고받는 API 가 있어서(근거 6장), 이 예외의 스택이 한 번 남으면 외부 호출 로그가 일부러 뺀
값이 그대로 나온다. 카카오와 토스 클라이언트는 이미 이 예외를 잡아 상태 코드로 분기하므로, 그 자리에서
원인 정보만 남기면 된다.

`DataIntegrityViolationException` 은 이 저장소에서 20곳 넘게 잡는다. 원인인 MySQL 드라이버의 예외는 중복
키 위반 때 위반한 값을 메시지에 담는다. 외부 계정 식별자처럼 개인정보가 고유 키인 테이블에서는 그
값이 개인정보다.

jjwt 의 `JwtException` 과 Spring Security 의 `JwtException` 은 메시지 내용을 확인하지 않은 채 미리 넣었다.
인증 토큰과 서명 검증을 다루는 예외라 메시지를 확인하는 수고보다 처음부터 막는 편이 싸고, 지금은 두 예외 모두
스택으로 찍히는 경로가 없어서 잃는 진단 정보가 없다. 원인 종류는 `exceptionType` 으로 가른다.

목록을 모든 외부 예외로 넓히지 않은 이유는 스택의 진단 가치 때문이다. 메시지가 안전한 예외까지 스택을
막으면 원인을 찾을 단서가 줄어든다. 목록 밖의 예외는 출력 단계의 마스킹이 거르고, 새로 민감한 메시지를
발견하면 목록에 더한다.

## 16. 런타임 동작을 어디서 확인했나

이 문서의 런타임 동작 주장은 아래 테스트가 현재 의존성 버전에서 확인한다. 테스트로 확인하지 못한 것은
본문에 확인하지 않았다고 적었다. 공통 로깅 문서 3개(`logging-guideline.md`, `logging-rationale.md`,
`logging-implementation-guide.md`)는 이 저장소의 어느 브랜치 이력에도 없어서 링크하지 않는다. 복구할지는
#703 이 이어서 정한다.

| 주장 | 확인하는 곳 |
|---|---|
| 외부 호출 콜백이 요청 스레드에서 돌면 MDC 가 남고, 다른 스레드에서 돌면 자동으로 전파되지 않는다 | `ExternalApiLoggingExchangeFilterTest` 의 응답과 오류 콜백 테스트 3개 |
| 스케줄러 작업이 실패하면 애스펙트와 Spring 오류 처리기가 스택을 각각 남긴다. Spring 쪽 메시지는 `Unexpected error occurred in scheduled task` 다.[R6][R7] | `SchedulerLoggingAspectTest.반복_작업이_실패하면_애스펙트와_Spring_오류_처리기가_스택을_각각_남긴다` |
| `%X{key:-default}` 는 기본값을 출력하고, 리터럴 괄호는 이스케이프하지 않으면 뒤의 메시지가 사라진다 | `LogbackPatternTest` |
| 민감 키 값이 가려진다 | `HttpBodyLoggingFilterTest` 의 민감 키 테스트 |
| Loki 쿼리의 `json` 뒤 예시 | 확인하지 못했다. 수집한 JSON 에 `message` 필드가 있고 그 값이 logfmt 모양이라는 전제다.[R12] `lc-infra` 의 실제 레이블과 JSON 구조는 이번 PR 에서 확인하지 않았다 |

## 17. 외부 자료

본문의 `[R번호]` 가 가리키는 자료다. 규칙 문서(`logging-project-guideline.md`)는 이 목록을 복사하지 않는다.
버전을 고정할 수 있는 자료는 이 저장소가 쓰는 버전(Spring Boot 4.0.5, Spring Framework 7.0.6,
Logback 1.5.32, Reactor Core 3.8.4, Tomcat 11.0.20, logstash-logback-encoder 8.0)에 맞췄다.

구분은 셋이다. `직접` 은 자료가 본문의 사실을 그대로 말한다. `조건부` 는 자료는 말하지만 본문의 적용에
조건이나 범위가 붙는다. `교체` 는 자료를 원문으로 바꿔 인용해야 한다. `열람` 열은 이 작업에서 주소를 직접
열어 내용을 확인했는지를 적는다.

| 번호 | 구분 | 자료 | 본문에서 근거로 쓰는 것 | 열람 |
|---|---|---|---|---|
| R1 | 직접 | [Logback AsyncAppender](https://logback.qos.ch/manual/appenders-async-sift.html) | 큐가 차면 이벤트를 버리는 조건과 `discardingThreshold`, `maxFlushTime` 의 뜻. 매뉴얼에 버전 고정 주소가 없다 | 아니오 |
| R2 | 직접 | [Logback PatternLayout](https://logback.qos.ch/manual/layouts.html) | `%F` 의 비용, 리터럴 괄호 이스케이프, `%X{key:-default}`. `LogbackPatternTest` 가 1.5.32 에서 같은 결과를 낸다 | 아니오 |
| R3 | 직접 | [`ShortenedThrowableConverter` 8.0](https://github.com/logfellow/logstash-logback-encoder/blob/logstash-logback-encoder-8.0/src/main/java/net/logstash/logback/stacktrace/ShortenedThrowableConverter.java) | 프레임 줄이기와 예외 메시지를 그대로 출력하는 동작 | 아니오 |
| R4 | 조건부 | [Reactor FAQ, MDC](https://projectreactor.io/docs/core/3.8.4/reference/faq.html#faq.mdc) | MDC 가 스레드에 붙고 Reactor 컨텍스트는 구독에 붙는다. 이 저장소에서 `traceId` 가 빠지는지는 콜백 스레드에 달려 있어 테스트로 가른다 | 예 |
| R5 | 직접 | [Reactor 스케줄러](https://projectreactor.io/docs/core/3.8.4/reference/coreFeatures/schedulers.html) | 스케줄러를 바꾸면 실행 스레드가 바뀐다 | 예 |
| R6 | 직접 | [Spring `TaskUtils` 7.0.6](https://github.com/spring-projects/spring-framework/blob/v7.0.6/spring-context/src/main/java/org/springframework/scheduling/support/TaskUtils.java) | 반복 작업의 기본 오류 처리기가 `Unexpected error occurred in scheduled task` 로 스택을 남긴다 | 예 |
| R7 | 조건부 | [`ThreadPoolTaskScheduler` 7.0.6](https://docs.spring.io/spring-framework/docs/7.0.6/javadoc-api/org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler.html) | 오류 처리기를 따로 두지 않았을 때의 기본 처리. 이 저장소의 실제 스케줄러 빈 구성은 확인하지 않았다 | 예 |
| R8 | 조건부 | [Tomcat 11.0 Remote IP Valve](https://tomcat.apache.org/tomcat-11.0-doc/config/valve.html#Remote_IP_Valve) | `internalProxies` 의 기본값과 첫 프록시의 헤더 제거 요구. 헤더를 처리하는 순서는 문서에 없다 | 예 |
| R9 | 직접 | [Prometheus `absent`](https://prometheus.io/docs/prometheus/latest/querying/functions/#absent) | 시계열이 없을 때 결과를 만드는 함수 | 아니오 |
| R10 | 직접 | [Prometheus 잡과 인스턴스](https://prometheus.io/docs/concepts/jobs_instances/) | 스크랩할 때 `job` 라벨이 자동으로 붙는다 | 아니오 |
| R11 | 조건부 | [Prometheus 설정, `honor_labels`](https://prometheus.io/docs/prometheus/latest/configuration/configuration/) | `honor_labels` 가 `false` 일 때 충돌한 라벨이 `exported_` 접두사로 바뀐다. `lc-infra` 의 값은 확인하지 못했다 | 아니오 |
| R12 | 조건부 | [Loki 로그 쿼리](https://grafana.com/docs/loki/latest/query/log_queries/) | `json`, `line_format`, `logfmt` 파서 문법. 수집한 JSON 의 모양은 `lc-infra` 에 달려 있다 | 아니오 |
| R13 | 교체 | [개인정보의 안전성 확보조치 기준 제8조](https://www.law.go.kr/LSW/admRulSideInfoP.do?admRulSeq=2100000281400&chrClsCd=010201&docCls=jo&joNo=0008&joBrNo=00) | 접속기록의 관리 대상과 보존 기간. 현행 국가법령정보센터 원문이며 자동 열람이 되지 않아 사람이 원문과 대조해야 한다 | 아니오 |
| R14 | 조건부 | [개인정보 보호법 제2조](https://www.law.go.kr/법령/개인정보보호법) | 다른 정보와 쉽게 결합해 알아볼 수 있는 정보도 개인정보다. 자동 열람이 되지 않아 사람이 원문과 대조해야 한다 | 아니오 |
| R15 | 조건부 | [Spring Boot 트레이싱](https://docs.spring.io/spring-boot/4.0/reference/actuator/tracing.html) | Micrometer Tracing 을 쓰면 `traceId` 와 `spanId` 를 MDC 로 로그에 상관시킨다. 이 저장소에는 추적 의존성이 없다 | 예 |
| R16 | 직접 | [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html) | 인증 성공과 실패를 보안 이벤트로 기록하라는 권고 | 아니오 |
