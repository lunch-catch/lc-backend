# 엔티티 생성 패턴 점검 항목의 근거

이 문서는 [entity-creation-guideline.md](./entity-creation-guideline.md)의 점검 항목이 왜 필요한지를 구체적인 예시와 함께 설명한다.
원칙의 출처는 Joshua Bloch의 Effective Java(3판) 아이템 1, 2, 15, 17이며, 이 문서의 설명과 예시는 모두 새로 작성한 것이다.

모든 항목은 하나의 질문으로 모인다. **어떤 경로로 만들어도 무효한 엔티티가 존재할 수 없는가.**

## 1. Lombok 사용 방침

### 왜 Lombok을 부분적으로만 쓰는가

이펙티브 자바는 Lombok 없이 모든 코드를 손으로 작성한다. 그러나 실무에서 접근자나 기본 생성자까지 손으로 쓰는 부담은 크다.

기준은 **그 애너테이션이 설계 원칙을 건드리는가**다.

| 성격 | 판단 |
|------|------|
| 상태를 바꾸지 않는 반복 코드 (`@Getter`) | Lombok에 맡긴다 |
| 생성 경로, 필수 강제, 불변식 검증 | 손으로 작성한다 |

`@Getter`는 원칙과 무관한 보일러플레이트지만, `@Setter`는 가변성을 열어 불변식을 무너뜨린다.
같은 Lombok이라도 둘의 성격이 전혀 다르다.

### 어떤 @Builder를 쓰지 않는가

기준은 하나다. **필수 강제가 컴파일 시점에 남는가.**

```java
// 클래스 레벨 @Builder: 필수값 누락이 런타임에야 터진다
Order.builder().totalPrice(10000).build();   // memberId 누락. 컴파일 통과

// 오버로딩 정적 팩터리: 컴파일이 안 된다
Order.place(10000);   // 컴파일 에러
```

클래스 레벨 `@Builder`는 여기에 더해 `id`를 노출하고(R3 위반) 모든 필드를 선택으로 만든다. **그래서 금지한다.**

**생성자 레벨은 다르다.** `access = AccessLevel.PRIVATE` 을 주면 Lombok이 만든 인자 없는 `builder()`가 private이 되어 밖에서 못 부른다.
그 위에 필수를 파라미터로 받는 정적 진입점을 얹으면 컴파일 강제가 그대로 남는다.

```java
@Builder(access = AccessLevel.PRIVATE)
private Order(Long memberId, int totalPrice, String memo, String giftMessage) { ... }

public static OrderBuilder builder(Long memberId, int totalPrice) {
    return Order.builder().memberId(memberId).totalPrice(totalPrice);
}
```

```java
Order.builder();          // 컴파일 에러: builder() has private access
Order.builder(1L);        // 컴파일 에러: 인자 두 개가 필요하다
```

생성자에 붙이므로 `id`도 빌더에 들어가지 않는다(R3 준수).
**초판은 "검증 생성자에 붙인 `@Builder`도 인자 없는 `builder()`를 만들어 같은 문제를 낳는다" 고 적었으나, `access = PRIVATE` 을 빠뜨린 판단이었다.**

## 2. 표준 골격

### 왜 검증을 private 생성자 한곳에 모으는가

생성 경로가 여럿이어도 **모두 이 생성자를 통과하게 만들면 검증이 한 번만 작성된다.**

```java
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "total_price", nullable = false)
    private int totalPrice;

    private String memo;           // 선택
    private String couponCode;     // 선택

    // 검증과 대입은 이 생성자 한곳에 모은다. private 이라 외부에서 못 부른다.
    private Order(Long memberId, int totalPrice, String memo, String couponCode) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId는 필수다");
        }
        if (totalPrice < 0) {
            throw new IllegalArgumentException("totalPrice는 음수일 수 없다: " + totalPrice);
        }
        this.memberId = memberId;
        this.totalPrice = totalPrice;
        this.memo = memo;
        this.couponCode = couponCode;
    }

    public static Order place(Long memberId, int totalPrice) {
        return new Order(memberId, totalPrice, null, null);
    }

    public static Order place(Long memberId, int totalPrice, String memo) {
        return new Order(memberId, totalPrice, memo, null);
    }

    public static Order place(Long memberId, int totalPrice, String memo, String couponCode) {
        return new Order(memberId, totalPrice, memo, couponCode);
    }
}
```

호출부는 `null`을 넘길 필요가 없고, 필수 두 개는 어떤 팩터리를 써도 빠뜨릴 수 없다.

```java
Order a = Order.place(1L, 25000);
Order b = Order.place(1L, 25000, "부재 시 경비실");
Order c = Order.place(1L, 25000, "부재 시 경비실", "WELCOME10");

Order.place(1L);   // 컴파일 에러
```

### 왜 오버로딩 증가를 감수하는가

선택 필드가 늘면 팩터리 조합이 함께 는다. 이 비용은 **의미 있는 생성 시나리오만 정의하는 것**으로 관리한다.

선택 필드의 모든 조합(2의 n승)을 만들지 않는다.
만들어야 할 조합이 계속 늘어난다면, 그 엔티티의 생성 시나리오가 정말 그렇게 다양한지 다시 검토하는 신호로 읽는다.

### 왜 선택 필드가 많으면 빌더로 가는가

선택 필드가 늘면 오버로딩이 감당이 안 된다. 빌더는 **선택 필드 추가가 필드 하나 + 메서드 하나**로 끝난다.

생성자 레벨 `@Builder(access = PRIVATE)` 로 충분하므로 대부분은 손으로 쓸 일이 없다.
아래는 Lombok으로 표현할 수 없는 제약(단계별 빌더 등)이 필요할 때의 원형이며, 아이템 2가 제시한 형태다.
**필수를 빌더 생성자로 받아 컴파일 강제를 유지한다**는 점은 두 방식이 같다.

```java
// 정적 팩터리로 빌더 진입점을 연다. 필수 두 개를 여기서 받는다.
public static Builder place(Long memberId, int totalPrice) {
    return new Builder(memberId, totalPrice);
}

public static class Builder {
    private final Long memberId;   // 필수: 빌더 생성자로 강제
    private final int totalPrice;  // 필수
    private String memo;           // 선택
    private String giftMessage;    // 선택

    private Builder(Long memberId, int totalPrice) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId는 필수다");
        }
        this.memberId = memberId;
        this.totalPrice = totalPrice;
    }

    public Builder memo(String memo)            { this.memo = memo; return this; }
    public Builder giftMessage(String message)  { this.giftMessage = message; return this; }
    // 선택 필드 추가 = 필드 하나 + 메서드 하나. 오버로딩 없음.

    public Order build() {
        return new Order(this);
    }
}
```

```java
Order order = Order.place(1L, 25000)   // 필수 강제
        .memo("부재 시 경비실")
        .build();

Order.place(1L).build();               // 컴파일 에러
```

대가는 이 코드를 손으로 쓰는 것이다. Lombok `@Builder`로는 필수 강제가 런타임으로 내려가므로 대체하지 않는다.

## 3. 필수 규칙

### R1. 왜 기본 생성자가 protected인가

JPA는 프록시 생성과 리플렉션 인스턴스화를 위해 기본 생성자를 요구한다.
public으로 열면 **상태가 비어 있는 엔티티를 누구나 만들 수 있으므로** protected가 하한선이다.

### R2. 왜 생성 경로를 정적 팩터리 하나로 좁히는가

네 가지 이유가 있고, 첫 번째가 이 규칙 전체의 존재 이유다.

1. **검증을 강제한다.** `new`와 `builder()`를 열어 두면 검증 생성자를 거치지 않는 경로가 생긴다. 팩터리 하나로만 노출하면 모든 생성이 검증 생성자를 통과할 수밖에 없다.
2. **이름으로 생성 의도를 드러낸다.** 생성자는 클래스 이름으로만 불려 같은 시그니처의 시나리오를 구분하지 못한다. `Order.place(...)`와 `Order.reorder(...)`는 호출부에서 의도가 드러난다. (아이템 1)
3. **필수 필드를 컴파일 시점에 강제한다.** 빌더는 필수와 선택을 구분하지 못해 누락이 런타임에야 터진다.
4. **내부 조립 방식을 숨긴다.** 호출부는 `Order.place(...)`만 알므로 안에서 무엇을 쓰든 나중에 교체해도 호출부를 건드리지 않는다.

리뷰 기준이 "public 생성자나 public `builder()`가 있으면 지적"이라는 단일 규칙으로 검사 가능해지는 것도 부수 효과다.

### R3. 왜 id를 생성 파라미터에서 빼는가

`id`를 외부에서 채울 수 있으면 신규 생성 의도인데도 `save()`가 persist가 아니라 **merge로 흐른다.**

| 상황 | 결과 |
|------|------|
| 존재하지 않는 id | 불필요한 SELECT 후 INSERT |
| 존재하는 id | **남의 행을 덮어쓴다** |

`GenerationType.IDENTITY`를 쓰는 이상 id는 DB가 채우는 값이다.

### R4. 왜 내부가 결정하는 값에 외부 입력을 받지 않는가

초기 상태를 외부가 지정할 수 있으면 상태 전이 규칙이 무의미해진다.

```java
// 외부가 처음부터 SHIPPED 로 만들 수 있으면 ship() 의 전제 조건 검사가 우회된다
Order.place(1L, 10000, OrderStatus.SHIPPED);
```

생성 시각도 마찬가지다. 외부가 과거 시각을 넣으면 정렬과 집계가 어긋난다.

### R4-1. 왜 DTO에서 검증했는데 엔티티에서 또 하는가

표면적으로 중복이지만 **방어 계층이 다르다.**

DTO 검증만 믿으면 컨트롤러를 거치지 않는 경로가 뚫린다.
배치 잡, 메시지 컨슈머, 관리자 기능, 테스트 코드는 `@Valid`를 통과하지 않으므로, 엔티티가 스스로를 지키지 않으면 이 경로들에서 무효 데이터가 그대로 들어간다.

R2가 생성 경로를 하나로 좁히는 것과 짝을 이룬다. **경로를 좁히고, 그 좁혀진 경로가 반드시 검증하게 만드는 것이다.**

```sql
-- MySQL 8.4 는 CHECK 제약을 실제로 강제하므로 최종 방어선까지 세울 수 있다
ALTER TABLE orders ADD CONSTRAINT chk_total_price CHECK (total_price >= 0);
```

다만 다른 필드의 도메인 맥락이 필요한 규칙(예: 쿠폰 할인 적용 후 금액이 원가를 넘을 수 없다)은 DTO에 넣지 않는다.
DTO는 그 맥락을 모르므로 엔티티나 도메인 서비스에만 둔다.

### R6. 왜 @Data가 특히 위험한가

`@Data`는 `@Setter`와 `@EqualsAndHashCode`를 **함께 끌고 들어온다.**
가변성을 열고, 연관관계 필드가 있으면 `equals`/`hashCode`/`toString`이 지연 로딩을 건드려 예상치 못한 쿼리와 순환 참조를 일으킨다.

애너테이션 하나가 세 가지 문제를 동시에 만드는 유형이라 별도 항목으로 둔다.

### R7. 왜 상태 변경을 도메인 메서드로만 하는가

setter로 열면 전이 전제 조건 검사가 호출하는 쪽마다 흩어진다.

```java
// 점검 대상: 전이 조건 검사가 서비스마다 중복되고, 하나는 반드시 빠진다
if (order.getStatus() == OrderStatus.PAID) {
    order.setStatus(OrderStatus.SHIPPED);
}
```

엔티티에 메서드를 두면 조건이 한곳에 모이고, 서비스 코드에 흩어진 상태 검사 중복이 사라진다.

## 4. 권장 규칙

### G1. 왜 팩터리 이름이 도메인 행위를 드러내야 하는가

`of`, `create`가 남발되면 정적 팩터리의 이점 하나(의도를 이름으로 드러내는 것)가 사라진다.
그러면 생성자 대비 남는 이점은 검증 강제뿐인데, 그것만이면 이름을 고민할 이유가 없어져 규칙이 형해화된다.

마땅한 이름이 떠오르지 않으면 **생성 시나리오가 정말 하나뿐인지 다시 검토하는 신호**로 읽는다.

### G2. 왜 기본값을 생성자 본문에서 처리하는가

기본값 로직이 생성자 한곳에 모여 **어떤 팩터리로 만들어도 동일하게 적용된다.**
팩터리마다 기본값을 넣으면 새 팩터리를 추가할 때 하나를 빠뜨린다.

Lombok `@Builder.Default`를 쓰지 않는 이유는 리플렉션 기반 인스턴스화 경로와 섞여 기대와 다르게 동작할 수 있기 때문이다.

### G3. 왜 hashCode를 고정값으로 두는가

영속화 전에는 id가 `null`이다. id 기반으로 `hashCode`를 계산하면 저장 전후로 해시가 바뀐다.

```java
Set<Order> set = new HashSet<>();
set.add(order);          // id == null 일 때의 해시로 버킷 결정
orderRepository.save(order);   // id 부여됨
set.contains(order);     // 다른 버킷을 보게 되어 false
```

**컬렉션에서 객체를 잃어버리는 것**을 막기 위해 `getClass().hashCode()`로 고정한다.
성능상 모든 인스턴스가 같은 버킷에 들어가지만, 엔티티를 대량으로 해시 컬렉션에 넣는 경우가 드물어 실질 비용이 작다.

### G4. 왜 ToString에서 연관 필드를 제외하는가

지연 로딩 필드를 문자열로 만들면 **로그 한 줄이 추가 쿼리를 부른다.**
양방향 연관이 있으면 서로를 참조하며 무한 재귀로 이어진다.

### G5. 왜 테스트 픽스처를 별도로 두는가

생성 경로를 좁히면 테스트에서 특정 상태의 엔티티를 만들기가 번거로워진다.
그 불편을 프로덕션 코드에 setter를 여는 것으로 해결하면 **R2가 무너진다.**

테스트 소스에 픽스처를 두면 프로덕션 규칙을 지키면서 편의를 얻는다.
id가 반드시 필요한 테스트는 `ReflectionTestUtils.setField`로 주입하거나 `@DataJpaTest`에서 실제로 저장해 부여받는다.

## 5. 엔티티 속성값의 저장 방식

### 왜 "코드성 값"이라 부르지 않는가

초판의 제목은 "코드성 값의 저장 방식"이었다. **"코드성"이 값이 아니라 개념을 가리키는 말로 읽혔다.**

상품 카테고리처럼 코드처럼 생긴 개념이 이 장으로 들어와, 세 방식 중 가장 가까운 방식 2(대리키 + `code` UNIQUE)에 맞춰졌다.
필요도 없는 `code` 컬럼이 딸려 오고, 정작 상위 분류나 노출 순서 같은 진짜 속성은 자리를 못 잡는다.

**제목이 단위를 정한다.** "속성값"이라고 하면 대상이 행의 컬럼 하나라는 것이 분명해져 개념이 들어올 자리가 없다.

| | 이 장의 대상 | 이 장의 대상이 아닌 것 |
|---|---|---|
| 정체 | 엔티티가 갖는 속성 | 엔티티 그 자체 |
| 예 | `OrderStatus`, `StorageType`, 결제 수단 | 상품 카테고리, **회원 등급**, 공급처 |
| 판별 | 후보값 중 하나를 고르는 것이 전부다 | 계층, 노출 순서, 이미지, 활성 여부가 딸린다 |

### 세 방식과 비교

**방식 1: 문자열 코드를 PK로 (특수 케이스 예외)**

참조 측은 `region_code VARCHAR`를 FK로 갖는다. 조인 없이 FK만 봐도 값을 안다.

**방식 2: bigint 대리키 + code UNIQUE (코드 테이블 채택)**

참조 측은 `claim_reason_id BIGINT`를 FK로 갖는다. 값을 알려면 조인이 필요하지만 코드 테이블은 작고 캐싱 가능해 상쇄된다.

**방식 3: enum + `@Enumerated(EnumType.STRING)` (기본 채택)**

별도 테이블이 없다. 참조 측은 `storage_type VARCHAR` 컬럼에 상수 이름을 문자열로 저장한다.

| 항목 | 방식 1 (문자열 PK) | 방식 2 (대리키+UNIQUE) | 방식 3 (enum) |
|------|-------------------|----------------------|--------------|
| 별도 테이블 | 있음 | 있음 | 없음 |
| 참조 FK | region_code (값 직독) | claim_reason_id (조인 필요) | 컬럼에 문자열 |
| 조인 발생 | 없음 | 있음 (캐싱으로 상쇄) | 없음 |
| 런타임 값 추가 | 가능 | 가능 | 불가 (배포 필요) |
| PK bigint 통일 방침 | 위배 | 부합 | 해당 없음 |
| 베이스 엔티티 상속 | 불가 | 가능 | 해당 없음 |
| 코드 유지보수 (타입 안전) | 없음 | 없음 | 있음 (컴파일 검출) |
| 운영 데이터 유지보수 | 좋음 | 좋음 | 나쁨 (배포 필요) |

**절대 우위는 없고 이기는 축이 다르다.**
방식 1은 운영 데이터 관리와 조인 회피, 방식 3은 타입 안전성, 방식 2는 프로젝트 일관성에서 우위다.

### 왜 기본이 enum인가

후보값이 정해진 속성은 대개 다음 세 조건을 만족한다.

1. 값의 종류가 고정적이다 (자주 늘지 않는다)
2. 값을 개발자가 배포로 관리한다 (운영자 화면 관리 요구가 없다)
3. **값에 로직이 딸린다**

세 번째가 결정적이다. 새 값 추가가 곧 코드 변경이므로, 테이블의 "행만 추가하면 된다"는 유연성이 **쓸모가 없다.**
그러면 테이블, FK, 조인, 캐싱을 유지하는 비용만 남는다.

### 왜 정책의 유무가 아니라 관리 주체가 기준인가

"정책이 딸려 있으니 테이블"이라는 판단이 흔하지만 틀렸다.
정책이 있어도 개발자가 코드로 관리하는 것이면 enum이 적합하고, 정책은 enum의 필드(값)나 메서드(규칙)로 표현한다.

운영자가 화면에서 런타임에 바꿔야 하는 것만 테이블로 간다.

### 왜 코드 테이블이 방식 2인가

정수 PK를 택한 대가로 얻는 것이 크다.
**코드 테이블도 공통 베이스 엔티티를 그대로 상속해 "코드 테이블만 예외"가 사라진다.**

조인 비용은 실질적으로 작다. 코드 테이블은 행이 적고(사유 코드 5~20개) 값이 거의 안 바뀌어 캐싱으로 대부분 제거된다.
참조 행에서 `claim_reason_id`만 읽고 문구는 메모리 캐시에서 꺼내면 DB 조인이 발생하지 않는다.

### 왜 ORDINAL을 금지하는가

`@Enumerated`의 기본값이 `ORDINAL`이라 명시하지 않으면 순서 기반으로 저장된다.

```java
// 상수 사이에 하나를 끼워 넣으면 기존 데이터의 의미가 통째로 밀린다
public enum StorageType { ROOM, COLD, FROZEN }           // COLD = 1
public enum StorageType { ROOM, CHILLED, COLD, FROZEN }  // COLD = 2, 기존 1은 CHILLED 가 된다
```

이미 저장된 행을 건드리지 않았는데 의미가 바뀌는 유형이라 발견이 늦다.

### enum의 약점과 관리

유일한 약점은 상수 이름이 곧 DB 저장값이라 **이름을 바꾸면 전 행 마이그레이션이 필요**하다는 것이다. 두 가지로 관리한다.

- 표시 문구는 `displayName` 필드로 분리한다. 문구가 바뀌어도 저장값은 건드릴 일이 없어 이름을 바꿀 이유 자체가 거의 사라진다.
- 상수 이름은 표시 문구가 아니라 안정적인 영문 식별자로 짓는다.

저장 식별자 변경은 방식 1과 2에서도 참조 데이터와 캐시 정리를 요구하므로 enum만의 문제가 아니다.

enum에서 테이블로의 이관은 저장 형식이 문자열로 이어져 부담이 작다. **지금 enum으로 두는 것이 나중 확장을 막지 않는다.**
