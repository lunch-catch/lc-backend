# 엔티티 생성 패턴 리뷰 가이드

이 문서는 JPA 엔티티 인스턴스의 생성 방식을 코드 리뷰 점검 항목으로 정리한 가이드다.
`@Entity`가 붙은 클래스와 `@Embeddable` 값 타입이 추가되거나 변경되는 PR에 적용한다.

기준 스택은 Java, Spring Data JPA, MySQL 8.4, Lombok이다.
각 항목이 왜 필요한지는 [entity-creation-rationale.md](./entity-creation-rationale.md)를 참고한다.
이 문서는 [effective-java-guideline.md](./effective-java-guideline.md)의 객체 생성 항목(아이템 1, 2, 15, 17)과 [jpa-rdb-guideline.md](./jpa-rdb-guideline.md)의 엔티티 설계 항목을 엔티티 생성이라는 한 지점에 맞춰 구체화한 것이다.

핵심 원칙은 하나다. **엔티티는 유효하지 않은 상태로 존재할 수 없어야 한다.**
생성 경로가 여럿이면 그중 하나는 반드시 검증을 빠뜨리므로, 경로를 하나로 좁히고 그 하나에 검증을 모은다.

> **예시에 관하여.** 코드 예시는 설명을 위한 것이며 특정 도메인을 전제하지 않는다.
> `Order`, `place`, `couponCode`처럼 도메인에 종속된 이름이 나오더라도 패턴을 보여주기 위한 임의의 예시다.

## 1. Lombok 사용 방침

설계의 핵심(생성 경로 통제, 필수 강제, 불변식 검증)은 손으로 작성하고, 그 외 반복 코드만 Lombok에 맡긴다.

| Lombok | 방침 | 이유 |
|--------|------|------|
| `@Getter` | 허용 | 접근자는 상태를 바꾸지 않는다 |
| `@NoArgsConstructor(access = PROTECTED)` | 허용 | JPA가 요구하는 기본 생성자를 안전한 수준으로 만든다 |
| `@Builder` (생성자 레벨, `access = PRIVATE`) | 허용 | 진입점이 필수를 파라미터로 받으면 컴파일 강제가 유지된다 |
| `@Builder` (클래스 레벨) | 금지 | 필수 강제가 컴파일 시점에서 런타임으로 내려가고 `id`가 노출된다 |
| `@Setter`, `@Data` | 금지 | 가변성을 열어 불변식을 무너뜨린다 (아이템 17 위배) |
| 클래스 레벨 `@AllArgsConstructor` | 금지 | `id`를 노출하고 모든 필드를 선택으로 만든다 |
| `@EqualsAndHashCode`, `@ToString` (전 필드) | 금지 | 연관 필드를 건드려 지연 로딩과 순환 참조를 유발한다 |

점검 항목
* `EC-1-01` 엔티티에 `@Setter`, `@Data`가 붙어 있지 않은가
* `EC-1-02` 클래스 레벨 `@Builder`, `@AllArgsConstructor`가 없는가
* `EC-1-03` `@EqualsAndHashCode`, `@ToString`이 연관 필드를 포함하지 않는가

## 2. 필수 규칙

위반 시 `[BLOCKER]`로 지적한다.

### R1. 기본 생성자는 protected로 둔다

점검 항목
* `EC-2-01` `@NoArgsConstructor(access = AccessLevel.PROTECTED)`가 있는가
* `EC-2-02` 기본 생성자가 public이 아닌가

### R2. 외부에 노출되는 생성 경로는 하나뿐이고, 그 경로가 필수를 강제한다

**지키려는 것은 둘이다.**

1. 검증을 모으는 private 생성자를 우회할 수 없다
2. 필수 필드를 빠뜨리면 **컴파일이 안 된다**

`public` 생성자, 필수를 받지 않는 `public builder()`, `@Setter`를 통한 사후 조립은 셋 다 위 둘을 깬다.

기본은 정적 팩터리 메서드다. 선택 필드가 많아 오버로딩이 감당이 안 되면 빌더를 쓰되,
**진입점이 필수를 파라미터로 받아야 한다** (R5의 예외).

```java
Order.place(1L, 10000)              // 정적 팩터리. 기본
Order.builder(1L, 10000)...build()  // 빌더. 필수를 받으므로 허용
Order.builder()...build()           // 금지. 필수가 선택이 된다
new Order(...)                      // 금지
```

점검 항목
* `EC-2-03` public 생성자 또는 필수를 받지 않는 public `builder()`가 있는가
  **인자 없는** `builder()`가 public이면 필수 강제가 런타임으로 내려간다. 필수를 파라미터로 받는 진입점은 해당하지 않는다.
* `EC-2-04` 사후에 setter로 조립하는 호출부가 있는가

```java
// 점검 대상: 사후 조립. 검증 생성자를 우회한다
Order order = new Order();
order.setMemberId(1L);
order.setTotalPrice(10000);

// 개선: 유일한 생성 경로
Order order = Order.place(1L, 10000);
```

### R3. 식별자는 생성 파라미터에 포함하지 않는다

점검 항목
* `EC-2-05` `id`를 외부에서 세팅할 수 있는가
  `save()`가 persist가 아니라 merge로 흘러 남의 행을 덮어쓸 수 있다.

### R4. 내부가 결정하는 값은 외부 입력을 받지 않는다

초기 상태, 생성 시각, 집계값처럼 규칙에 의해 정해지는 필드는 생성자 본문에서 설정한다.

점검 항목
* `EC-2-06` 상태 필드를 외부에서 지정할 수 있는가

```java
this.status = OrderStatus.CREATED;      // 외부가 SHIPPED 로 만들 수 없다
this.createdAt = LocalDateTime.now();   // 또는 @CreatedDate 로 위임
```

### R4-1. 필드 검증은 DTO에 있어도 엔티티 생성자에서 다시 한다

`@Valid`가 붙은 DTO에서 이미 검증했더라도 같은 검증을 엔티티 생성자에도 둔다.

| 계층 | 검증 성격 | 목적 |
|------|-----------|------|
| DTO (`@Valid`) | 형식 검증 | 빠른 실패, 사용자 친화적 에러 응답 |
| 엔티티 생성자 | 불변식 보장 | 어떤 경로로도 무효 상태 차단 |
| DB (`NOT NULL`, `CHECK`) | 최종 방어선 | 앞 두 계층을 우회한 데이터 차단 |

점검 항목
* `EC-2-07` 필수 필드 검증이 private 생성자에 있는가
* `EC-2-08` 다른 필드의 도메인 맥락이 필요한 규칙을 DTO에 넣지 않았는가
  DTO는 그 맥락을 모른다. 엔티티나 도메인 서비스에 둔다.

### R5. 필수는 생성 진입점의 파라미터로 강제한다

필수 필드는 모든 팩터리의 앞자리 파라미터로 받아 컴파일 시점에 강제하고, 선택 필드는 뒤에 붙는 오버로딩으로 받는다.

점검 항목
* `EC-2-09` 필수 필드가 팩터리 파라미터로 컴파일 시점에 강제되는가
* `EC-2-10` 필수 강제가 풀린 채로 `@Builder`를 썼는가
  클래스 레벨 `@Builder`가 대표적이다. 생성자 레벨 + `access = PRIVATE` 로 진입점이 필수를 받는다면 해당하지 않는다.
* `EC-2-11` 선택 필드의 모든 조합(2의 n승)을 만들지 않고 의미 있는 시나리오만 오버로딩했는가

```java
// 준수: 필수는 팩터리 파라미터로 강제, 선택은 오버로딩으로 받음
public static Order place(Long memberId, int totalPrice) { ... }
public static Order place(Long memberId, int totalPrice, String memo) { ... }

Order.place(1L);   // 컴파일 에러: 필수 두 개 필요
```

**선택 필드가 많으면 빌더를 쓴다.** 오버로딩이 감당이 안 되는 지점부터다.
예외 승인 대상이 아니라 정식 선택지이며, **진입점이 필수를 파라미터로 받는다는 조건**만 지키면 된다.

| 선택 필드 수 | 방식 |
|---|---|
| 0~2 | 정적 팩터리 오버로딩 |
| 3 이상 | 생성자 레벨 `@Builder(access = PRIVATE)` + 필수를 받는 정적 진입점 |

**핵심은 `builder()`가 필수 필드를 파라미터로 받는 것이다.** 그러면 빠뜨렸을 때 컴파일이 안 된다.

Lombok 으로 된다. **`@Builder` 를 생성자에 붙이고 `access = PRIVATE` 로 감춘 뒤, 필수를 받는 정적 메서드를 따로 둔다.**

```java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long memberId;          // 필수
    private int totalPrice;         // 필수
    private String memo;            // 선택
    private String couponCode;      // 선택
    private String giftMessage;     // 선택
    private OrderStatus status;     // 내부가 결정 (R4)

    /* 생성자에 붙이므로 id 는 빌더에 들어가지 않는다 (R3).
       access = PRIVATE 라 Lombok 이 만든 무인자 builder() 를 외부에서 못 부른다. */
    @Builder(access = AccessLevel.PRIVATE)
    private Order(Long memberId, int totalPrice,
                  String memo, String couponCode, String giftMessage) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId 는 필수다");
        }
        if (totalPrice < 0) {
            throw new IllegalArgumentException("totalPrice 는 0 이상이어야 한다: " + totalPrice);
        }
        this.memberId = memberId;
        this.totalPrice = totalPrice;
        this.memo = memo;
        this.couponCode = couponCode;
        this.giftMessage = giftMessage;
        this.status = OrderStatus.CREATED;   // 외부 입력을 받지 않는다 (R4)
    }

    /* 외부 진입점. 필수를 파라미터로 받아 컴파일 시점에 강제한다. */
    public static OrderBuilder builder(Long memberId, int totalPrice) {
        return Order.builder()               // private builder(). 같은 클래스라 부를 수 있다
                .memberId(memberId)
                .totalPrice(totalPrice);
    }
}
```

```java
Order order = Order.builder(1L, 10000)
        .memo("문 앞에 놓아주세요")
        .couponCode("WELCOME10")
        .build();

Order.builder();       // 컴파일 에러: builder() has private access
Order.builder(1L);     // 컴파일 에러: 인자 두 개가 필요하다
```

**두 `builder` 는 시그니처가 달라 오버로딩된다.** Lombok 이 만든 무인자 쪽은 private 이라 밖에서 안 보이고,
밖에서 보이는 것은 필수를 받는 쪽뿐이다.

**무엇이 어떻게 다른가**

| | 클래스 레벨 `@Builder` | 생성자 레벨 + `access = PRIVATE` | 직접 작성한 정적 멤버 빌더 |
|---|---|---|---|
| 필수 강제 | **런타임.** 사실상 없다 | **컴파일 시점** | 컴파일 시점 |
| `id` 노출 | **노출된다** | 안 된다 | 안 된다 |
| 검증 위치 | `build()` 뒤로 흩어진다 | 생성자 한곳 | 생성자 한곳 |
| 코드량 | 한 줄 | 몇 줄 | **길다** |

**두 번째를 쓴다.** 세 번째와 같은 보장을 훨씬 적은 코드로 얻는다.
직접 작성한 정적 멤버 빌더는 Lombok 으로 표현할 수 없는 제약(단계별 빌더 등)이 필요할 때의 최후 수단이다.

`EC-1-02`(클래스 레벨 `@Builder` 금지)와 충돌하지 않는다. 금지 대상은 **클래스 레벨**이고 위는 생성자 레벨이다.
`EC-2-10`(생성 경로에 `@Builder`를 썼는가) 역시 필수 강제가 풀리는 경우를 두고 하는 말이며,
**위처럼 필수를 컴파일 시점에 강제했다면 해당하지 않는다.**

Effective Java 아이템 2가 이 패턴을 다루며, 이 문서는 거기에 **"필수는 빌더 진입점의 파라미터로 받는다"** 는 제약을 더한 것이다.

### R6. `@Data`, `@Setter`, `@AllArgsConstructor`는 엔티티에 쓰지 않는다

점검 항목
* `EC-2-12` 세 애너테이션이 엔티티에 없는가

### R7. 상태 변경은 의미 있는 도메인 메서드로만 한다

```java
// 점검 대상: 전이 조건 검사가 서비스로 흩어진다
order.setStatus(OrderStatus.SHIPPED);

// 개선: 엔티티가 전제 조건을 스스로 지킨다
public void ship() {
    if (this.status != OrderStatus.PAID) {
        throw new IllegalStateException("결제 완료 상태에서만 배송할 수 있다: " + this.status);
    }
    this.status = OrderStatus.SHIPPED;
}
```

점검 항목
* `EC-2-13` 상태 변경이 setter가 아니라 도메인 메서드로 이루어지는가
* `EC-2-14` 그 메서드가 전이 전제 조건을 검사하는가

## 3. 권장 규칙

위반 시 `[MAJOR]` 또는 `[MINOR]`로 지적한다.

### G1. 정적 팩터리 이름은 도메인 행위를 드러낸다

| 상황 | 권장 이름 |
|------|-----------|
| 신규 생성 | `place`, `register`, `issue`, `open` |
| 기존 데이터로부터 파생 | `from`, `reorder`, `copyOf` |
| 여러 인자를 조합 | `of` (도메인 이름이 마땅치 않을 때만) |
| 외부 표현으로부터 복원 | `restore`, `parse` |

점검 항목
* `EC-3-01` 팩터리 이름이 `of`, `create`로 남발되지 않는가

### G2. 선택 필드 기본값은 생성자 본문에서 처리한다

점검 항목
* `EC-3-02` 기본값 설정이 검증을 모으는 private 생성자 한곳에 있는가
* `EC-3-03` Lombok `@Builder.Default`를 쓰지 않았는가

```java
private Order(Long memberId, int totalPrice, String memo, Integer quantity) {
    // ... 검증 ...
    this.memo = (memo != null) ? memo : "";
    this.quantity = (quantity != null) ? quantity : 1;
}
```

### G3. `equals`와 `hashCode`는 식별자 기준으로 직접 작성한다

**해시 컬렉션에 넣을 때만 필요하다.** 이 항목들은 그럴 때만 판정한다.

이 프로젝트는 FK를 `Long`으로 들고 JPA 연관을 매핑하지 않으므로(`IDS-6-03`) `Set` 연관이 생기지 않는다.
엔티티를 `HashSet`이나 `HashMap`에 담는 코드도 없다. **그래서 대부분의 엔티티에 `equals`와 `hashCode`가 없는 것이 정상이다.**

담는 코드가 생기면 그때 아래대로 작성한다.

점검 항목
* `EC-3-04` `@EqualsAndHashCode`를 쓰지 않고 직접 작성했는가
* `EC-3-05` `equals`가 식별자만 비교하는가
* `EC-3-06` `hashCode`가 고정값인가

```java
@Override
public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Order other)) return false;
    return id != null && id.equals(other.id);
}

@Override
public int hashCode() {
    return getClass().hashCode();
}
```

### G4. `@ToString`은 연관 필드를 제외한다

```java
@ToString(exclude = "items")
```

점검 항목
* `EC-3-07` `@ToString`이 지연 로딩 필드를 포함하지 않는가

### G5. 테스트 픽스처는 별도 팩터리에 모은다

점검 항목
* `EC-3-08` 프로덕션 코드에 테스트 전용 생성 수단(setter, 팩터리)이 없는가
* `EC-3-09` 테스트 소스에 픽스처 클래스가 있는가

```java
// src/test/java/.../OrderFixture.java
public final class OrderFixture {

    public static Order shipped(Long memberId) {
        Order order = Order.place(memberId, 10000);
        order.pay();
        order.ship();
        return order;
    }

    private OrderFixture() {
    }
}
```

## 4. 엔티티 속성값의 저장 방식

**대상은 엔티티가 갖는 속성 하나다.** 상태, 유형, 구분처럼 정해진 후보값 중 하나를 갖는 컬럼의 저장 표준이다.
**기본은 enum + `@Enumerated(EnumType.STRING)`**이다. 세 방식의 비교와 채택 근거는 rationale을 참고한다.

**개념 자체를 어떻게 만들지는 이 장이 다루지 않는다.**
상위 분류를 갖거나 노출 순서, 이미지, 활성 여부, 유효 기간 같은 속성이 딸리면 그것은 속성값이 아니라 **엔티티**다.
그런 것은 `Product`나 `Order`와 같은 급으로 만들고 이 장의 세 방식에 끼워 맞추지 않는다.

| | 이 장의 대상 | 엔티티 |
|---|---|---|
| 예 | 결제 수단, 주문 상태, 보관 타입 | 상품 카테고리, **회원 등급**, 공급처 |
| 이유 | 후보값 중 하나를 고르는 것이 전부다 | 할인율, 승급 기준, 상위 분류가 딸린다 |

**회원 등급을 이 장의 예로 들지 않는다.** 할인율과 승급 기준을 갖는 순간 값이 아니라 엔티티이며,
그것이 필요하면 `member_grade` 테이블로 만든다.

점검 항목
* `EC-4-01` 후보값이 정해진 속성에 enum을 썼는가
  운영자가 화면에서 런타임에 값을 바꿔야 하는 경우에만 테이블로 승격한다.
* `EC-4-02` `@Enumerated(EnumType.STRING)`을 명시했는가
  기본값 `ORDINAL`은 상수 순서만 바뀌어도 기존 데이터가 뒤틀린다.
* `EC-4-03` 사람에게 보일 문구를 상수 이름이 아니라 `displayName` 같은 필드로 분리했는가
* `EC-4-04` 저장 컬럼 `length`를 넉넉히(20~30) 두었는가
* `EC-4-05` 테이블로 승격한 경우 정수 대리키 + `code` UNIQUE 방식인가
  베이스 엔티티 규칙은 [base-entity-guideline.md](./base-entity-guideline.md)를 따른다.

판단 기준은 정책의 유무가 아니라 **그 정책을 누가 관리하는가**다.

| 상태값 상황 | 적합한 방식 |
|-------------|-------------|
| 구분만 필요, 정책 없음 | enum (상수 나열) |
| 정책 있으나 개발자가 코드로 관리 | enum + 필드/메서드 |
| 정책을 운영자가 화면에서 관리 | 코드 테이블 (정수 대리키 + code UNIQUE) |

```java
@Getter
@RequiredArgsConstructor
public enum StorageType {
    ROOM("실온", 25), COLD("냉장", 4), FROZEN("냉동", -18);
    private final String displayName;
    private final int maxCelsius;     // 값 정책은 필드로
}

public enum OrderStatus {
    CREATED, PAID, SHIPPED, DELIVERED, CANCELED;

    public boolean canShip() {        // 전이 규칙 정책은 메서드로
        return this == PAID;
    }
}
```

두 예 모두 **값 자체에 더 붙을 것이 없다.** 보관 타입에 상위 분류나 노출 순서가 생기지 않고,
주문 상태에 유효 기간이 생기지 않는다. 그래서 enum 으로 남는다.

## 5. 리뷰 체크리스트

| 확인 항목 | 강도 |
|-----------|------|
| 엔티티에 public 생성자 또는 public `builder()`가 있는가 | `[BLOCKER]` |
| `@Setter`, `@Data`가 붙어 있는가 | `[BLOCKER]` |
| `id`를 외부에서 세팅할 수 있는가 | `[BLOCKER]` |
| `@NoArgsConstructor`가 없거나 public인가 | `[BLOCKER]` |
| 필수 필드 검증이 private 생성자에 있는가 | `[BLOCKER]` |
| 필수 필드가 팩터리 파라미터로 컴파일 시점에 강제되는가 | `[BLOCKER]` |
| 상태 필드를 외부에서 지정할 수 있는가 | `[MAJOR]` |
| 필수 강제가 풀린 채로 `@Builder`를 썼는가 (클래스 레벨 등) | `[MAJOR]` |
| `@EqualsAndHashCode`, `@ToString`이 연관 필드를 포함하는가 | `[MAJOR]` |
| 프로덕션 코드에 테스트 전용 생성 수단이 있는가 | `[MAJOR]` |
| `@Enumerated`에 `STRING`을 명시하지 않았는가 | `[MAJOR]` |
| 선택 필드 조합을 불필요하게 많이 오버로딩했는가 | `[MINOR]` |
| 정적 팩터리 이름이 도메인 행위를 드러내는가 | `[MINOR]` |

## 6. 적용 범위와 예외

### 적용 대상

`@Entity`가 붙은 모든 클래스와 `@Embeddable` 값 타입. 도메인이 달라도 생성 패턴은 이 문서를 공통으로 따른다.

### 적용하지 않는 대상

- 요청과 응답 DTO: 역직렬화를 위해 기본 생성자와 setter가 필요할 수 있다. 대신 `record`와 생성자 바인딩을 우선 검토한다.
- 조회 전용 프로젝션 DTO: 생성자 표현식(`SELECT new ...`)으로 만들어지므로 public 생성자가 필요하다.
- MapStruct 등 매퍼가 생성하는 객체.

### 예외 허용 절차

규칙을 벗어나야 할 이유가 있으면 PR 설명에 근거를 남기고 리뷰어 합의를 받는다.
합의된 예외는 코드 주석이 아니라 이 문서에 사례로 추가해 다음 사람이 같은 논의를 반복하지 않게 한다.

## 7. 참고

- Joshua Bloch, Effective Java 3rd Edition, 아이템 1(정적 팩터리), 아이템 2(빌더), 아이템 15(접근 제어 최소화), 아이템 17(가변성 최소화)
- Lombok `@Builder` 공식 문서: https://projectlombok.org/features/Builder
- Lombok `@NoArgsConstructor` 공식 문서: https://projectlombok.org/features/constructor
- Hibernate ORM User Guide, Entity 요구사항: https://docs.jboss.org/hibernate/orm/6.4/userguide/html_single/Hibernate_User_Guide.html
