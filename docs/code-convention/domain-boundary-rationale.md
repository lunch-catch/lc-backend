# 도메인 경계: 설계 근거 (rationale)

점검 항목은 [domain-boundary-guideline.md](./domain-boundary-guideline.md) 에 있다.

**이 문서는 규칙의 내용을 설명하지 않는다.** 계층이 셋인 이유, 도메인을 11개로 나눈 이유,
의존 방향을 그렇게 정한 이유는 [런치캐치_백엔드_설계.md](../architecture/런치캐치_백엔드_설계.md)
1장과 2장이 소유한다. 같은 설명을 여기 옮기면 어긋나는 순간 기준이 사라진다.

이 문서가 답하는 것은 셋이다.

1. 왜 그것이 **리뷰 항목**인가. 빌드가 막아 주는데 사람이 또 봐야 하는 이유
2. 위반이 **코드에서 어떤 모양**으로 나타나는가
3. 테스트가 **못 보는 것**은 무엇이고 왜 그런가

---

## 1. 빌드가 막는데 왜 리뷰 항목인가

`ArchitectureTest` 12개가 `./gradlew check` 에서 돌고 `G-BUILD` 가 필수 상태 검사다.
어기면 병합이 막힌다. 그런데도 번호 붙인 점검 항목을 둔 이유가 셋이다.

**첫째, 테스트는 "무엇이 틀렸다" 만 말하고 "무엇을 해야 한다" 는 말하지 않는다.**
위반 메시지는 이렇게 나온다.

```
coupon 가 campaign 의 내부를 직접 참조한다: Constructor
<com.launchcatch.coupon.service.CouponIssueService.<init>(
 com.launchcatch.campaign.repository.CampaignRepository)>
```

사실은 정확하지만 다음 행동이 적혀 있지 않다. 항목 번호가 있으면 `DPB-2-01` 하나로
"무엇을 어겼고 어디를 읽으면 되는지" 가 함께 간다.

**둘째, 리뷰가 빌드보다 먼저 온다.** 작성자는 push 전에 지적을 받는 편이 싸다.
CI 가 10분 뒤에 빨간불을 주는 것보다 리뷰가 그 자리에서 짚는 쪽이 왕복을 줄인다.

**셋째, 자동으로 막히지 않는 항목이 여섯이다.** 아래 3장에서 다룬다. 이 여섯은
번호가 없으면 리뷰에서 체계적으로 확인할 방법이 없다.

### 왜 번호가 필요한가

번호 없이도 리뷰는 된다. 실제로 그렇게 돌려 봤고, 결과가 둘로 갈렸다.

자동 리뷰 봇이 같은 PR 에서 JPA 연관 위반은 번호로 집고 경계 위반은 문장을 인용했다.

```
JPA 연관:  "다른 도메인 엔티티를 객체로 참조하지 않고 ID 필드로 두었는지(AGG-1-01)"
경계:      "contract 에는 인터페이스와 record, enum 만 둔다"
관리자:    "Admin must not depend on other business domains"
```

마지막 줄이 문제다. 번호가 없으면 봇이 문장을 인용하고, 그 문장이 지식 베이스를
거치면서 **영어로 번역됐다.** 한국어로 쓰기로 한 규약(봇 규칙 6)이 번호가 없는 영역에서만
깨진 것이다. 번호는 번역되지 않는다.

그리고 `CODEREVIEW.md` 의 중복 지적 방지 표가 번호 단위로 소유를 정한다. 번호가 없는
영역은 그 표에서 "설계 문서 1장과 2장" 이라는 넓은 덩어리로만 가리킬 수 있었다.

---

## 2. 위반은 어떤 모양으로 나타나는가

규칙을 일부러 어긴 코드를 올려 확인했다. 12개 중 9개가 걸리고 위반 28건이 보고됐다.
자주 나오는 모양 넷을 적어 둔다.

### 2.1 저장소를 주입받는 모양 (`DPB-2-01`)

가장 흔하다. 다른 도메인의 데이터가 필요할 때 그 도메인의 `Repository` 를 주입받는 것이
제일 짧은 길이기 때문이다.

```java
// 걸린다
class CouponIssueService {
    private final CampaignRepository campaignRepository;   // campaign.repository
}

// 계약을 지난다
class CouponIssueService {
    private final CouponIssueConditionQueryService issueConditionQueryService;  // campaign.contract
}
```

**생성된 코드를 통해서도 새어 나간다.** QueryDSL 이 만드는 `Q` 클래스가 그렇다.
`Coupon` 엔티티가 `Campaign` 을 객체로 들고 있으면 `QCoupon` 이 `QCampaign` 을 참조하고,
그 경로로도 같은 규칙이 걸린다. 손으로 쓴 import 만 보고 넘기면 놓친다.

### 2.2 `contract` 에 구현이 들어가는 모양 (`DPB-1-02`, `DPB-1-03`)

계약과 구현을 한 파일에 두면 편하다. 그래서 `contract` 에 `@Component` 가 생긴다.

```java
// 걸린다. 인터페이스도 record 도 enum 도 아니고, 저장소를 주입받고 엔티티를 반환한다
@Component
public class CampaignBudgetReader {
    private final CampaignRepository campaignRepository;
    public Campaign read(Long id) { ... }
}
```

이것이 두 항목에 동시에 걸리는 이유가 있다. `DPB-1-02` 는 **형태**(클래스가 있다)를 보고
`DPB-1-03` 은 **노출**(엔티티와 저장소가 계약 표면에 드러난다)을 본다. 형태만 고쳐
인터페이스로 바꿨는데 반환 타입이 그대로 엔티티면 `DPB-1-03` 이 남는다.

### 2.3 `global` 이 도메인을 아는 모양 (`DPB-3-01`)

표시용 포매터나 유틸을 `global` 에 두면서 도메인 타입을 끌어들이는 경우다.

```java
// global 이 campaign 을 안다
public final class CampaignBudgetFormatter {
    public static String format(CampaignBudgetReader reader, Long campaignId) { ... }
}
```

**계약 인터페이스로 바꿔도 이 위반은 남는다.** `global` 이 `campaign.contract` 를
의존하는 것 자체가 규칙 위반이기 때문이다. 고치는 방향은 계약을 다듬는 것이 아니라
그 코드를 `global` 밖으로 옮기는 것이다.

### 2.4 이벤트를 발행 도메인 안쪽에 두는 모양 (`DPB-4-03`)

```java
// 걸린다
package com.launchcatch.coupon.service;
public record CouponIssuedEvent(...) { }
```

수신자가 그 타입을 쓰려면 `coupon.service` 를 import 해야 한다. **통보로 지운 간선이
import 로 되살아난다.** 이벤트를 쓴 이유가 사라지는 자리다.

### 2.5 순환은 쌍으로만 보인다 (`DPB-4-02`)

`순환_의존이_없다` 는 패키지 첫 세그먼트로 슬라이스를 잘라 사이클을 찾는다. 그래서
`coupon -> campaign` 하나만 있으면 아무 말도 하지 않고, 반대 방향이 생기는 순간
둘을 함께 가리킨다. **나중에 간선을 더하는 사람이 먼저 있던 간선 때문에 막힌다.**
방향 판단(`DPB-4-01`)을 리뷰가 보는 이유가 이것이다. 테스트는 사후에만 말한다.

---

## 3. 테스트가 못 보는 것

여섯 항목은 `ArchitectureTest` 가 막지 못한다. 왜 자동화되지 않는지 적어 둔다.

### 3.1 다른 도메인의 `service` 를 주입받는 길이 열려 있다 (`DPB-2-03`)

**이것이 가장 큰 구멍이다.** 패키지를 도메인 우선으로 두면 `..service..` 패턴이 모든
도메인의 서비스를 한 계층으로 묶는다. 그래서 아래가 `계층_의존_규칙` 을 통과한다.

```java
// campaign 의 service 를 coupon 의 service 가 직접 부른다. 통과한다
class CouponIssueService {
    private final CampaignStatusService campaignStatusService;
}
```

Service 가 Service 를 부르는 것으로 보이기 때문이다. 막히는 것은 `entity` 와
`repository` 참조뿐(`DPB-2-01`)이다.

관리자만 예외다. `관리자는_다른_업무_도메인을_의존하지_않는다` 가 `admin` 패키지에서
나가는 모든 간선을 보므로 `admin -> coupon.service` 는 걸린다. **나머지 도메인 쌍에는
그 방어가 없다.** 패턴으로 막으려면 "다른 도메인의 service 를 참조하지 않는다" 를 더해야
하는데, 그러면 같은 도메인 안에서 service 가 service 를 부르는 정상 경로까지 걸린다.
도메인 이름을 비교하는 조건을 직접 써야 하고, 그것은 `도메인_내부는_같은_도메인에서만_쓴다`
의 `INTERNAL_SEGMENTS` 에 `service` 를 더하는 것으로 가능하다. 지금 더하지 않은 이유는
`contract` 구현이 `service` 에 있어 계약 호출과 구현 직접 호출을 ArchUnit 이 구분하지
못하기 때문이다. 리뷰가 보는 쪽으로 남겨 두었다.

### 3.2 무엇을 공개할지는 의도의 문제다 (`DPB-1-01`)

`contract` 에 인터페이스를 두면 테스트는 통과한다. 그 인터페이스를 **공개할 필요가
있었는지** 는 코드 형태로 드러나지 않는다. 쓰는 도메인이 하나도 없는 계약도 통과하고,
내부에서만 쓰면 되는 것을 계약으로 올려도 통과한다.

### 3.3 권한 검사를 손으로 구현한 것은 보이지 않는다 (`DPB-3-04`)

`auth` 를 쓰지 않고 서비스 안에서 역할 문자열을 비교해도 구조는 성립한다. 호출 패턴으로
가려지지 않으므로 리뷰가 읽어야 한다.

### 3.4 설계 판단은 코드에 남지 않는다 (`DPB-4-05`, `DPB-4-06`)

직접 호출과 이벤트 중 무엇을 골랐는지는 결과만 코드에 있고 이유는 없다. 스케줄러가
작업 내용을 직접 구현했는지도 "그 코드가 어느 패키지에 있는가" 로는 판정되지 않는다.

### 3.5 규칙이 잠들어 있을 수 있다

12개 모두 `allowEmptyShould(true)` 를 달고 있다. 검사 대상이 0개면 실패하지 않고
통과한다. 도메인 패키지가 비어 있는 동안은 대부분이 그 상태다. **규칙이 초록인 것과
규칙이 지켜진 것이 같지 않다.** 첫 클래스가 생기는 순간부터 문다.

---

## 4. 다른 가이드와 겹칠 때

| 사안 | 소유 | 왜 |
|---|---|---|
| 다른 도메인 엔티티를 객체로 참조 | `AGG-1-01` | 연관 매핑을 더 좁게 다룬다. `DPB-2-02` 는 발화하지 않는다 |
| `fetch = LAZY` 명시 | `AGG-4-01` | 경계가 아니라 매핑 방식이다 |
| 접근 제어자 최소화 | `EJ-3-01` | 경계와 무관한 자리에도 적용된다 |
| `contract` 의 record 에 로직 | `DPB-1-02` | 형태를 보는 것이라 경계가 소유한다 |

마지막 줄이 실제로 걸렸던 자리다. `contract` 의 record 에 시간대 판정 메서드를 둔 적이
있는데, record 이므로 `contract에는_인터페이스_record_enum만_둔다` 는 **통과했다.**
설계 문서 1.3절이 "`contract` 에 로직이 들어갈 자리를 없앤다" 고 한 것과 어긋나지만
테스트가 잡지 못한다. `DPB-1-02` 를 리뷰에서 볼 때 형태만 보지 말고 **동작이 있는지**
함께 봐야 하는 이유다.

## 관련 문서

* 점검 항목: [domain-boundary-guideline.md](./domain-boundary-guideline.md)
* 규칙의 내용과 근거: [런치캐치_백엔드_설계.md](../architecture/런치캐치_백엔드_설계.md) 1장과 2장
* 강제 수단: `src/test/java/com/launchcatch/ArchitectureTest.java`
* 연관 매핑: [jpa-association-guideline.md](./jpa-association-guideline.md) (`AGG-`)
