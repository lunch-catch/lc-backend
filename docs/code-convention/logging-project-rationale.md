# 로깅: 설계 근거 (rationale)

점검 항목은 [logging-project-guideline.md](./logging-project-guideline.md) 에 있다.

**이 문서는 규칙의 내용을 설명하지 않는다.** 장치를 그렇게 둔 이유, appender 가 로그를 버리는 이유,
마스킹 키를 고른 이유는 [로그_설계.md](../observability/로그_설계.md) 가 소유한다.
같은 설명을 여기 옮기면 어긋나는 순간 기준이 사라진다.

이 문서가 답하는 것은 셋이다.

1. 왜 그것이 **리뷰 항목**인가. 빌드가 막지 않는데 번호 붙인 항목으로 두는 이유
2. 위반이 **코드에서 어떤 모양**으로 나타나는가
3. 테스트가 **못 보는 것**은 무엇이고 왜 그런가

---

## 1. 빌드가 막지 않는데 왜 리뷰 항목인가

도메인 경계는 `ArchitectureTest` 가 대부분을 빌드에서 막는다. 로깅은 그런 규칙이 없다.
테스트가 막는 항목은 `LOG-7-08` 하나이고, 그것도 테스트에 적힌 키 목록만 본다(3.1절).
**그래서 로깅 항목은 리뷰가 유일한 방어선이다.** 리뷰에서 놓치면 그대로 병합된다.

로그는 빠뜨려도 기능이 멀쩡하다. 성공 로그를 커밋 전에 남겨도, 민감한 예외의 스택을 그대로 남겨도
테스트는 초록이다. 틀린 것은 장애가 났을 때 로그를 읽는 순간에야 드러난다. 그때는 이미 늦다.

### 왜 번호가 필요한가

번호가 있으면 지적 하나에 "무엇을 어겼고 설계 문서의 어디를 읽으면 되는지" 가 함께 간다.
항목마다 근거 절을 달아 두었으므로 `LOG-7-13` 하나로 설계 문서 6.4절의 목록까지 이어진다.

번호는 공통 로깅 규칙 4판의 `LOG-*` 를 그대로 쓴다. 코드 주석과 지난 리뷰의 지적이 이미 이 번호를 쓴다.
번호를 새로 매기면 같은 규칙이 두 번호로 불린다. 그 대신 번호가 이어지지 않는다.
이 저장소의 문서에 그 문장이 없는 번호(코드 주석의 `LOG-2-03`, `LOG-5-02`)는 점검 항목으로 두지 않았다.

---

## 2. 위반은 어떤 모양으로 나타나는가

규칙을 일부러 어긴 코드를 올려 확인하지는 않았다. 아래 모양은 설계 문서가 적은 사건과 장치의 주석에서 옮겼다.

### 2.1 커밋 전에 남긴 성공 로그 (`LOG-6-01`)

트랜잭션 안에서 성공을 바로 남기는 것이 가장 짧은 길이라 가장 흔하다.

```java
// 롤백되면 일어나지 않은 일이 성공으로 남는다
@Transactional
public void pause(Long campaignId) {
    campaign.pause();
    log.info("event=CAMPAIGN_PAUSED campaignId={}", campaignId);
}

// 커밋된 뒤에만 남는다
@Transactional
public void pause(Long campaignId) {
    campaign.pause();
    TransactionLogging.afterCommit(() -> log.info("event=CAMPAIGN_PAUSED campaignId={}", campaignId));
}
```

로그를 읽는 사람은 성공 로그를 보고 상태가 바뀌었다고 믿는다. DB 에는 바뀐 것이 없다.

### 2.2 직접 만든 WebClient (`LOG-8-07`)

```java
// 타임아웃, 커넥션 풀, 외부 호출 로그가 모두 빠진다
WebClient client = WebClient.builder().baseUrl("https://kapi.kakao.com").build();

// global/config 가 붙인 것을 그대로 받는다
WebClient client = webClientBuilder.baseUrl("https://kapi.kakao.com").build();
```

빠진 것은 호출이 잘 될 때 보이지 않는다. 상대가 응답하지 않는 날 그 요청 스레드가 무기한 묶이고, 그 호출의 로그도 없다(설계 문서 7.1절).

### 2.3 스택으로 남긴 외부 예외 (`LOG-7-13`)

```java
// 예외 메시지에 쿼리 문자열까지 든 URI 가 실린다. 인가 코드와 토큰이 그대로 남는다
} catch (WebClientResponseException e) {
    log.warn("event=KAKAO_TOKEN_EXCHANGE_FAILED", e);
}

// 원인 정보만 남긴다
} catch (WebClientResponseException e) {
    log.warn("event=KAKAO_TOKEN_EXCHANGE_FAILED details={}", FailureLogDetails.from(e));
}
```

외부 호출 로그는 일부러 경로만 남긴다(설계 문서 6.3절). 스택 한 줄이 그 결정을 되돌린다.

### 2.4 표시하지 않은 예상된 거절 (`LOG-5-04`, `LOG-8-06`)

선착순의 소진과 혼잡은 4xx, 5xx 이지만 정상 운영의 답이다. 에러 코드에 `isExpectedTraffic()` 를 두지 않으면 접근 로그가 이 응답을 바디까지 남긴다.
이벤트 한 번이 만 줄을 남기고, 그 양이 로그 큐를 채워 요청 스레드를 막은 적이 있다(설계 문서 5.1절, 9장).

코드에서는 새 에러 코드를 더하면서 `isExpectedTraffic()` 를 정의하지 않은 모양으로 나타난다. 그 줄만 보면 틀린 곳이 없다.

### 2.5 헤더에서 직접 읽은 클라이언트 IP (`LOG-8-05`)

```java
// X-Forwarded-For 의 앞쪽 항목은 클라이언트가 보낸 값이다. 위조된 IP 가 로그에 남는다
String ip = request.getHeader("X-Forwarded-For").split(",")[0];

// 로드 밸런서가 덧붙인 마지막 항목을 쓴다
String ip = ClientIpResolver.resolve(request);
```

`MdcLoggingFilter`, `AdminLoginAuditFilter`, `AuthRateLimitFilter` 가 같은 값을 써야 로그와 감사 기록과 횟수 제한이 같은 IP 를 본다(설계 문서 3.1절).

---

## 3. 테스트가 못 보는 것

### 3.1 민감 키 테스트는 적힌 목록만 본다 (`LOG-7-08`)

`HttpBodyLoggingFilterTest` 의 민감 키 테스트는 테스트 안의 키 목록마다 값이 가려지는지 본다.
설정에서 키를 빼면 실패한다. **새 필드를 설정과 그 목록에 함께 빠뜨리면 통과한다.**
새 필드가 민감한지는 그 필드를 더한 사람만 안다. 그래서 요청과 응답의 DTO 에 필드가 늘면 리뷰가 이 항목을 본다.

### 3.2 WebClient 우회를 막는 빌드 규칙이 빠져 있다 (`LOG-8-07`)

이전 프로젝트는 `ArchitectureTest` 의 규칙으로 직접 생성을 빌드에서 막았다. 이 저장소로 옮겨 오면서 규칙이 빠졌다(설계 문서 7.1절).
지금 `src/main` 에 직접 생성하는 코드는 없다. 그래서 첫 위반은 리뷰만 잡는다.

### 3.3 커밋 뒤인지는 호출의 모양으로 가려지지 않는다 (`LOG-6-01`)

`log.info` 한 줄은 트랜잭션 안이든 밖이든 같은 모양이다. 그 메서드가 트랜잭션 안에서 불리는지는 호출하는 쪽까지 따라가야 안다.

### 3.4 무엇이 예상된 거절이고 무엇이 개인정보인지는 업무의 판단이다 (`LOG-5-04`, `LOG-8-06`, `LOG-7-03`)

정상 운영에서 대량으로 나오는 거절인지, 그 값이 개인정보인지는 코드의 형태에 드러나지 않는다. 기능을 설계한 사람이 정한다.

### 3.5 장치를 우회한 코드는 정상으로 보인다 (`LOG-1-01`, `LOG-4-01`, `LOG-4-04`, `LOG-8-05`, `LOG-8-09`, `LOG-8-11`)

업무 코드가 접근 로그를 한 번 더 남겨도, IP 를 헤더에서 직접 읽어도, 감사 기록을 로그로 남겨도 컴파일되고 동작한다.
장치를 썼는지 막는 규칙이 없으므로 리뷰가 장치 목록(설계 문서 2장, 4장)과 견준다.

---

## 4. 다른 가이드와 겹칠 때

| 사안 | 소유 | 왜 |
|---|---|---|
| 예외를 응답으로 바꾸는 흐름과 `GlobalExceptionHandler` 가 남기는 것 | [response-exception-flow.md](./response-exception-flow.md) | 응답 봉투와 예외 흐름을 다룬다. 이 가이드는 업무 코드가 남기는 로그만 본다 |
| 예상된 거절을 에러 코드에 표시하는 것(`isExpectedTraffic()`) | `LOG-5-04` | 에러 코드의 모양이 아니라 접근 로그의 양을 정하는 표시라 로깅이 소유한다 |
| 로그 내용과 상관관계 ID, 민감정보 마스킹이 충분한가 | `CODEREVIEW.md` 의 중복 지적 방지 표 | 이 가이드는 그것을 이 저장소의 장치로 어떤 모양으로 쓰는지를 본다. 충분한지는 그 표가 가리키는 가이드가 본다 |

## 관련 문서

* 점검 항목: [logging-project-guideline.md](./logging-project-guideline.md)
* 규칙의 내용과 근거: [로그_설계.md](../observability/로그_설계.md)
* 강제 수단: `src/test/java/com/lunchcatch/global/logging/HttpBodyLoggingFilterTest.java`
* 예외 흐름: [response-exception-flow.md](./response-exception-flow.md)
