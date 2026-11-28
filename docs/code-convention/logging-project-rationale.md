# 로깅 규칙의 근거: 런치캐치 백엔드

이 문서는 [logging-project-guideline.md](./logging-project-guideline.md) 의 도구와 규칙이 왜 지금
모양인지를 설명한다. 공통 장치와 설정이 왜 지금 모양인지는 [로그_설계.md](../observability/로그_설계.md) 가 갖는다. 이 저장소에만 해당하는 사정과 외부 자료가 보장하는 범위를 적는다. 같은
내용이 코드 주석에도 있으면 코드 주석이 기준이고, 이 문서는 흩어진 주석을 한곳에서 찾게 해 준다.
코드 주석이 틀렸으면 이 문서가 바로잡은 내용을 적는다.
이 문서는 공통 규칙 4판(2026-10-21)을 기준으로 쓴다. `[R번호]` 는 17장의 외부 자료를 가리킨다.

---

## 1. 운영 appender 가 로그를 버리는 이유

이 근거는 [로그 설계](../observability/로그_설계.md#5-버리는-쪽을-고른-appender)가 갖는다.

## 2. 예상된 대량 거절을 DEBUG 로 내리는 장치

공통 규칙 `LOG-5-04` 와 `LOG-8-06` 의 이 저장소 구현이다.

선착순 쿠폰의 소진과 혼잡은 4xx, 5xx 응답이지만 정상 운영에서 나오도록 설계한 답이다. 재고가
1만인데 2만 명이 오면 절반이 소진을 받는다. 이 응답을 접근 로그 규칙대로 남기면 이벤트 한 번이
바디를 실은 만 줄을 남긴다. [로그 설계 5장](../observability/로그_설계.md#5-버리는-쪽을-고른-appender)의 사건이 바로 이 경로에서 났다.

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
때문이다. 바디를 남기는 Content-Type 을 텍스트 계열로 제한한 이유는 이미지
업로드 같은 바이너리를 UTF-8 로 억지로 디코딩해 깨진 문자를 남기고, 남기지 않는 정상 응답에서도
매번 캐싱하는 낭비를 막기 위해서다. 요청 바디의 캐시 한도(8192바이트)를 로그 길이 한도
(2000자)보다 넉넉히 잡은 이유는 한글이 UTF-8 에서 글자당 최대 3바이트라서다. 바이트 한도를 글자 수와
같게 잡으면 남기기도 전에 글자가 중간에 잘린다.

## 4. trace_id 하나로 로그를 묶는 이유

이 근거는 [로그 설계](../observability/로그_설계.md#3-요청과-실행을-하나로-묶는-값)가 갖는다.

## 5. clientIp 를 검증하는 방식

`X-Trace-Id` 와 `X-Forwarded-For` 는 클라이언트가 마음대로 보낼 수 있는 헤더다. 그래서 허용 문자와
길이를 검사한다. 목적은 정확한 IP 형식 검사가 아니라 로그 인젝션(개행, 제어 문자)과 비정상적으로 긴
값을 막는 것이다.

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
   확인되지 않았다. 해석기는 그 확인 없이 지금 맞는 값을 낸다.
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
해석기가 돌면 위조한 값을 클라이언트 IP 로 돌려준다. 이 조합은 해석기로 막아 둔 IP 위조를 다시 들인다. 지금은
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
그 값이 샌다. 바디를 남기지 않는 이유는 `WebClient` 의 바디가 Publisher 라 가로채려면 따로
감싸야 하고 복잡도가 커지기 때문이다. 이 로그의 목적은 외부 API 가 느려서 우리가 멈췄는지를 증명하는
것이고, 상태 코드와 소요 시간이면 충분하다.

## 7. 스케줄러 게이지의 모양

이 근거는 [로그 설계](../observability/로그_설계.md#6-스케줄러-게이지)가 갖는다.

## 8. 로컬 설정의 모양

이 근거는 [로그 설계](../observability/로그_설계.md#41-로컬-설정의-모양)가 갖는다.

## 9. 운영 스택 설정의 값

이 근거는 [로그 설계](../observability/로그_설계.md#42-운영-스택-설정의-값)가 갖는다.

## 10. 관리자 로그인 감사 필터를 따로 둔 이유

관리자 로그인 감사의 설계와 선택 근거는 [관리자 API 설계의 로그인 감사 절](../design/admin/admin-api-design.md#로그인-감사)에 둔다.
아래는 도메인과 관계없이 감사 기록을 검토할 때 참고하는 법령과 보존의 한계다.

「개인정보의 안전성 확보조치 기준」 제8조는 개인정보취급자가 개인정보처리시스템에 접속해 처리한 업무 내역을
접속기록으로 관리하도록 한다. 일반적인 보존 기간은 1년이고 정보주체 수, 처리 정보, 사업자 조건 가운데 고시의
조건에 해당하면 2년이다. 접속기록이 위·변조와 도난, 분실되지 않게 보관해야 한다.[R13] 관리자 로그인 기록은
접속기록의 일부가 될 수 있지만 로그인 성공과 실패만으로 처리한 정보주체와 업무 내역까지 기록하지는 못한다.
따라서 로그인 기록만으로 제8조의 접속기록 요건을 충족한다고 단정하지 않는다. 인증 성공과 실패를 보안 이벤트로
기록하는 근거는 OWASP 로깅 지침이 더 직접적이다.[R16] 구체적인 법률 적용 범위와 보존 기간은 별도로 확인한다.
애플리케이션 로그는 보존 기간이 수집 설정을 따르고 큐가 차면 버려져 법률상 보존을 보장하지 못한다.

## 11. 액추에이터를 8081 로 나눈 이유

이 근거는 [로그 설계](../observability/로그_설계.md#7-액추에이터-포트)가 갖는다.

## 12. 이벤트명과 키를 바꾸는 방법

이벤트명이나 키를 바꿀 때는 그 이벤트에 걸린 쿼리와 알림을 같은 PR 에서 고친다.

롤링 배포 중에는 옛 버전과 새 버전의 인스턴스가 함께 돈다. 그동안은 옛 이름과 새 이름이 함께 찍히므로
쿼리와 알림이 둘 다 잡게 한다(`|~ "event=(OLD_NAME|NEW_NAME)"`). 배포가 끝나고 옛 이름이 더 찍히지 않으면
옛 이름을 지운다. 대시보드의 과거 구간은 옛 이름으로만 남아 있으므로, 과거와 견주는 패널은 옛 이름을
보존 기간 동안 남겨 둔다.

## 13. member, billing, analytics 의 레벨을 정한 근거

member, billing, analytics 의 레벨은 공통 규칙 `LOG-5-08` 에 따라 세 도메인의 담당자와 합의해 정했다.
판단은 하나의 질문으로 했다. 이 로그를 본 사람이 무엇을 해야 하는가, 그리고 하지 않으면 저절로 고쳐지는가.

**대사와 정합성 검사의 불일치** 소진 대사와 PG 대사가 찾은 불일치는 `settlement_mismatch` 에 미해결로
쌓인다. 관리자가 `/v1/admin/settlement-mismatches` 에서 보고 `MANUAL_ADJUSTMENT` 같은 처리로 닫는다.
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
정하지 않았다.

| 주장 | 확인하는 곳 |
|---|---|
| 외부 호출 콜백이 요청 스레드에서 돌면 MDC 가 남고, 다른 스레드에서 돌면 자동으로 전파되지 않는다 | `ExternalApiLoggingExchangeFilterTest` 의 응답과 오류 콜백 테스트 3개 |
| 스케줄러 작업이 실패하면 애스펙트만 스택을 남긴다. Spring 의 기본 오류 처리기는 같은 실패를 `Unexpected error occurred in scheduled task` 로 한 번 더 남기는데[R6][R7], `SchedulingConfig` 가 그 처리기를 바꿔 다시 남기지 않는다 | `SchedulerLoggingAspectTest.반복_작업이_실패하면_애스펙트만_스택을_남기고_스케줄러의_오류_처리기는_다시_남기지_않는다`, `SchedulingConfigTest.스케줄러의_오류_처리기는_실패를_다시_로그로_남기지_않는다` |
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
| R1 | 직접 | [Logback AsyncAppender](https://logback.qos.ch/manual/appenders-async-sift.html) | 큐가 차면 이벤트를 버리는 조건과 `discardingThreshold`, `maxFlushTime` 의 뜻. `maxFlushTime` 의 기본값은 매뉴얼에 없다. 매뉴얼에 버전 고정 주소가 없다 | 예 |
| R2 | 직접 | [Logback PatternLayout](https://logback.qos.ch/manual/layouts.html) | `%F` 의 비용, 리터럴 괄호 이스케이프, `%X{key:-default}`. `LogbackPatternTest` 가 1.5.32 에서 같은 결과를 낸다 | 예 |
| R3 | 직접 | [`ShortenedThrowableConverter` 8.0](https://github.com/logfellow/logstash-logback-encoder/blob/logstash-logback-encoder-8.0/src/main/java/net/logstash/logback/stacktrace/ShortenedThrowableConverter.java) | 프레임 줄이기와 예외 메시지를 그대로 출력하는 동작 | 예 |
| R4 | 조건부 | [Reactor FAQ, MDC](https://projectreactor.io/docs/core/3.8.4/reference/faq.html#faq.mdc) | MDC 가 스레드에 붙고 Reactor 컨텍스트는 구독에 붙는다. 이 저장소에서 `trace_id` 가 빠지는지는 콜백 스레드에 달려 있어 테스트로 가른다 | 예 |
| R5 | 직접 | [Reactor 스케줄러](https://projectreactor.io/docs/core/3.8.4/reference/coreFeatures/schedulers.html) | 스케줄러를 바꾸면 실행 스레드가 바뀐다 | 예 |
| R6 | 직접 | [Spring `TaskUtils` 7.0.6](https://github.com/spring-projects/spring-framework/blob/v7.0.6/spring-context/src/main/java/org/springframework/scheduling/support/TaskUtils.java) | 반복 작업의 기본 오류 처리기가 `Unexpected error occurred in scheduled task` 로 스택을 남긴다 | 예 |
| R7 | 조건부 | [`ThreadPoolTaskScheduler` 7.0.6](https://docs.spring.io/spring-framework/docs/7.0.6/javadoc-api/org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler.html) | 오류 처리기를 따로 두지 않았을 때의 기본 처리. 이 저장소의 실제 스케줄러 빈 구성은 확인하지 않았다 | 예 |
| R8 | 조건부 | [Tomcat 11.0 Remote IP Valve](https://tomcat.apache.org/tomcat-11.0-doc/config/valve.html#Remote_IP_Valve) | `internalProxies` 의 기본값과 첫 프록시의 헤더 제거 요구. 헤더를 처리하는 순서는 문서에 없다 | 예 |
| R9 | 직접 | [Prometheus `absent`](https://prometheus.io/docs/prometheus/latest/querying/functions/#absent) | 시계열이 없을 때 결과를 만드는 함수 | 예 |
| R10 | 직접 | [Prometheus 잡과 인스턴스](https://prometheus.io/docs/concepts/jobs_instances/) | 스크랩할 때 `job` 라벨이 자동으로 붙는다 | 예 |
| R11 | 조건부 | [Prometheus 설정, `honor_labels`](https://prometheus.io/docs/prometheus/latest/configuration/configuration/) | `honor_labels` 가 `false` 일 때 충돌한 라벨이 `exported_` 접두사로 바뀐다. `lc-infra` 의 값은 확인하지 못했다 | 예 |
| R12 | 조건부 | [Loki 로그 쿼리](https://grafana.com/docs/loki/latest/query/log_queries/) | `json`, `line_format`, `logfmt` 파서 문법. 수집한 JSON 의 모양은 `lc-infra` 에 달려 있다 | 예 |
| R13 | 교체 | [개인정보의 안전성 확보조치 기준 제8조](https://www.law.go.kr/LSW/admRulSideInfoP.do?admRulSeq=2100000281400&chrClsCd=010201&docCls=jo&joNo=0008&joBrNo=00) | 접속기록의 관리 대상과 보존 기간. 현행 국가법령정보센터 원문이다. 도구로는 본문이 열리지 않아 사람이 원문과 대조했고 본문 문장과 맞다 | 예 |
| R14 | 조건부 | [개인정보 보호법 제2조](https://www.law.go.kr/법령/개인정보보호법) | 제1호 나목. 해당 정보만으로는 알아볼 수 없어도 다른 정보와 쉽게 결합해 알아볼 수 있는 정보는 개인정보다. 쉽게 결합할 수 있는지는 다른 정보의 입수 가능성과 개인을 알아보는 데 드는 시간, 비용, 기술을 합리적으로 고려해 정한다. 도구로는 본문이 열리지 않아 사람이 원문과 대조했다 | 예 |
| R15 | 조건부 | [Spring Boot 트레이싱](https://docs.spring.io/spring-boot/4.0/reference/actuator/tracing.html) | Micrometer Tracing 을 쓰면 `traceId` 와 `spanId` 를 MDC 로 로그에 상관시킨다. 이 저장소에는 추적 의존성이 없다 | 예 |
| R16 | 직접 | [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html) | 인증 성공과 실패를 보안 이벤트로 기록하라는 권고 | 예 |
| R17 | 직접 | [Logback `AsyncAppenderBase` 1.5.32](https://github.com/qos-ch/logback/blob/v_1.5.32/logback-core/src/main/java/ch/qos/logback/core/AsyncAppenderBase.java) | `maxFlushTime` 의 기본값이 `DEFAULT_MAX_FLUSH_TIME = 1000` 밀리초다 | 예 |
