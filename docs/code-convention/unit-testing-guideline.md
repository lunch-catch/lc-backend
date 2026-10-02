# 단위 테스트 리뷰 가이드

이 문서는 Vladimir Khorikov의 Unit Testing(Manning, 2020)이 제시하는 단위 테스트 원칙을 코드 리뷰 점검 항목으로 정리한 가이드다.
테스트 코드가 포함되거나 변경되는 PR에 적용한다.

각 항목은 책의 원칙을 바탕으로 하되, 설명과 예시는 이 가이드에서 새로 작성했다.
상세한 근거와 배경은 원서를 참고한다. 이 문서는 책의 본문이나 코드를 옮긴 것이 아니라 원칙을 점검 기준으로 재구성한 것이다.
기본적인 테스트 유무 점검은 functional.md의 테스트 항목과 겹치며, 이 문서는 테스트의 설계와 품질을 더 깊게 다룬다.

기준 스택은 Java, JUnit, Spring, MySQL 8.4이다.

## 1. 좋은 테스트의 네 기둥

좋은 단위 테스트는 네 가지 속성을 균형 있게 갖춰야 한다.

점검 항목
* `UT-1-01` 회귀 방어: 테스트가 실제 버그를 잡아내는가
  의미 있는 로직을 실행하고 결과를 검증해야 회귀를 잡는다. 단순 getter만 호출하는 테스트는 방어력이 약하다.
* `UT-1-02` 리팩터링 내성: 동작이 그대로인데 내부 구현만 바꿔도 테스트가 깨지지 않는가
  구현 세부에 묶인 테스트는 리팩터링마다 깨져 거짓 경보를 낸다.
* `UT-1-03` 빠른 피드백: 테스트가 빠르게 실행되는가
  느린 테스트는 자주 돌리지 않게 되어 가치가 떨어진다.
* `UT-1-04` 유지보수성: 테스트가 읽기 쉽고 고치기 쉬운가
  테스트가 복잡하면 코드보다 테스트를 이해하는 데 시간이 더 든다.

이 중 리팩터링 내성이 가장 자주 간과되며, 거짓 경보가 쌓이면 팀이 테스트를 신뢰하지 않게 된다.

## 2. 무엇을 검증할 것인가

테스트는 내부 구현이 아니라 관찰 가능한 동작을 검증해야 한다.

점검 항목
* `UT-2-01` 결과나 상태 변화 등 외부에서 관찰 가능한 동작을 검증하는가
* `UT-2-02` 내부 메서드 호출 순서나 횟수 같은 구현 세부를 검증하지 않는가
* `UT-2-03` private 메서드를 직접 테스트하지 않고 공개 API를 통해 검증하는가
  private 메서드를 따로 테스트하고 싶어진다면, 그것이 별도 책임이라는 신호일 수 있다.

```java
// 점검 대상: 내부 호출을 검증해 구현에 묶임 (리팩터링에 취약)
verify(orderRepository, times(1)).save(any());

// 개선: 관찰 가능한 결과를 검증
Order saved = orderService.place(request);
assertThat(saved.getStatus()).isEqualTo(OrderStatus.PLACED);
```

## 3. 테스트 구조

테스트는 일관된 구조와 명확한 이름을 가져야 한다.

점검 항목
* `UT-3-01` 준비(given), 실행(when), 검증(then) 단계가 구분되는가
* `UT-3-02` 하나의 테스트가 하나의 동작 단위를 검증하는가
  한 테스트에 여러 실행(when)이 섞이면 무엇을 검증하는지 흐려진다.
* `UT-3-03` 테스트 이름이 어떤 상황에서 무엇을 기대하는지 드러내는가
* `UT-3-04` 조건 분기나 반복 같은 로직을 테스트 안에 넣지 않는가
  테스트에 로직이 들어가면 테스트 자체에 버그가 생길 수 있다.

```java
@Test
void 재고가_부족하면_주문에_실패한다() {
    // given
    Stock stock = new Stock(0);

    // when, then
    assertThatThrownBy(() -> stock.decrease(1))
        .isInstanceOf(OutOfStockException.class);
}
```

## 4. 테스트 더블

테스트 더블(mock, stub)은 목적에 맞게 구분해 써야 한다.

점검 항목
* `UT-4-01` 들어오는 데이터를 제공하는 의존성은 stub으로 두고 검증 대상으로 삼지 않는가
  stub과의 상호작용을 verify로 검증하면 구현에 묶인다.
* `UT-4-02` 나가는 호출(부수 효과)을 검증할 때만 mock으로 검증하는가
* `UT-4-03` 모든 의존성을 무분별하게 모킹하지 않는가
  과도한 모킹은 테스트를 구현 세부에 묶고 리팩터링 내성을 떨어뜨린다.

```java
// 점검 대상: 입력을 제공하는 stub과의 상호작용을 검증 (구현에 묶임)
when(memberReader.find(1L)).thenReturn(member);
verify(memberReader).find(1L);  // 불필요한 검증

// 개선: stub은 입력만 제공하고, 결과로 동작을 검증
when(memberReader.find(1L)).thenReturn(member);
Order order = orderService.place(request);
assertThat(order.getMemberId()).isEqualTo(1L);
```

## 5. 테스트 배치와 외부 의존성

**팀은 통합 테스트를 권장하지 않는다.** 기본은 단위 테스트다. 느리고, 깨지는 이유가 많고,
무엇이 틀렸는지 좁혀 주지 못한다. 리뷰에서 "통합 테스트를 쓰라" 고 요구하지 않는다.

**필요하다고 판단해 쓸 때는 `@SpringBootTest` 로 쓴다.** 실제 Spring 컨텍스트를 띄우고
여러 계층 또는 외부 인프라까지 연결해서 확인한다. `@DataJpaTest` 나 `@WebMvcTest` 같은
슬라이스는 쓰지 않는다. 슬라이스는 "실제로 붙여 봤다" 를 주지 않으면서 통합 테스트의
비용은 치르는 쪽이다. 인메모리 DB 도 쓰지 않는다. 방언과 잠금 동작이 달라 검증이 성립하지
않으므로 운영과 같은 `mysql:8.4` 와 `valkey 9` 를 띄운다(비기능 5행, 30행).

소스셋은 하나다. 단위 테스트와 통합 테스트가 모두 `src/test/java` 에 있다.

**`contract` 에는 테스트를 두지 않는다.** 그 패키지에는 인터페이스와 record, enum 만 있어
동작이 없다(설계 문서 1.3). 계약이 지켜지는지는 구현의 테스트가 본다. 구현은 같은 도메인의
`service` 에 있다.

**단위 테스트는 대상과 정확히 같은 패키지에, 통합 테스트는 도메인 패키지 바로 아래에 둔다.**
통합 테스트는 계층을 가로지르므로 대상이 한 패키지로 좁혀지지 않는다.

```
src/main/java/com/launchcatch/campaign/contract/CampaignQueryService.java     테스트 없음
src/main/java/com/launchcatch/campaign/service/CampaignStatusService.java

src/test/java/com/launchcatch/campaign/service/CampaignStatusServiceTest.java 같은 패키지
src/test/java/com/launchcatch/campaign/CampaignIntegrationTest.java           도메인 바로 아래
```

같은 패키지여야 package-private 클래스와 메서드에 닿는다.
`Controller` 와 `contract` 인터페이스의 구현체를 package-private 으로 두면 패키지가 어긋나는 순간
그 구현을 테스트할 수 없다.

이름은 단위가 `~Test`, 통합이 `~IntegrationTest` 다. **이름이 유일한 구분 수단이다.**
소스셋이 하나라 실행 기록이 `test.exec` 으로 모이므로, 커버리지 숫자가 단위 테스트에서 나온
것인지 계층을 가로지르는 테스트에서 나온 것인지 이름 말고는 가릴 방법이 없다.

**셋 다 빌드가 강제한다.** `TestPlacementTest` 가 `contract` 배치, 패키지 미러링,
`@SpringBootTest` 의 이름을 확인하며, 어기면 `./gradlew check` 가 실패해 병합이 막힌다.

외부 의존성은 종류에 따라 다르게 다뤄야 한다.

점검 항목
* `UT-5-01` 외부 결제 API처럼 우리가 통제할 수 없는 공유 의존성을 mock으로 대체하는가
  통제할 수 없는 것을 실제로 부르면 테스트가 남의 사정으로 깨진다.
* `UT-5-02` 통합 테스트를 쓸 때 `@SpringBootTest` 로 실제 컨텍스트를 띄우는가
  슬라이스나 인메모리 DB 로 대신하면 "실제로 붙여 봤다" 가 성립하지 않는다.
  **통합 테스트가 없다는 것만으로 지적하지 않는다.** 쓸지 말지는 작성자가 판단한다.
* `UT-5-03` 통합 테스트의 이름이 `~IntegrationTest` 로 끝나는가
  소스셋이 하나라 이름이 커버리지 해석의 유일한 단서다. `TestPlacementTest` 가 확인한다.

```java
// 점검 대상: 슬라이스로 통합을 흉내 낸다. 컨텍스트 일부만 떠서 실제 연동이 아니다
@DataJpaTest
class OrderRepositoryTest { }

// 개선: 꼭 필요하면 컨텍스트를 전부 띄우고 실제 인프라에 붙인다
@SpringBootTest
@Testcontainers
class OrderIntegrationTest {

    @Test
    void 주문을_저장하고_조회한다() { }
}
```

## 6. 테스트 코드 품질

테스트 코드도 운영 코드와 같은 수준으로 관리해야 한다.

점검 항목
* `UT-6-01` 테스트 코드의 가독성과 중복을 운영 코드만큼 신경 쓰는가
* `UT-6-02` 테스트 간에 상태를 공유해 서로 영향을 주지 않는가
  공유 상태는 실행 순서에 따라 결과가 달라지는 깨지기 쉬운 테스트를 만든다.
* `UT-6-03` 커버리지 숫자 자체를 목표로 삼지 않는가
  높은 커버리지가 곧 좋은 테스트는 아니다. 검증 없이 실행만 하는 테스트도 커버리지는 올린다.
