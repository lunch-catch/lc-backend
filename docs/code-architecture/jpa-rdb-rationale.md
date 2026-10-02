# JPA 사용 점검의 근거 (RDB 관점 유지)

이 문서는 [jpa-rdb-guideline.md](./jpa-rdb-guideline.md)의 점검 항목이 근거하는 원문이다.
가이드는 이 문서에서 코드 리뷰에 적용할 점검 항목만 추출해 정리한 것이며, 판단이 애매하거나 배경이 필요할 때 이 문서를 참고한다.

---

## 개요

> ORM은 좋은 도구지만, 정해진 용도 안에서 써야 한다. DB는 관계형 모델을 기반으로 동작하므로, 객체지향적인 사고를 그대로 DB에 밀어 넣으면 문제가 생긴다.

이 글은 "ORM을 쓰되 관계형 DB의 관점을 잃지 말자"는 생각을 JPA와 Spring 환경에 맞춰 정리한 내용이다.

---

## ORM의 장점과 적절한 사용 범위

### 1. ORM(JPA)이란 무엇이고 무엇이 좋은가?

ORM(Object Relational Mapping)은 객체와 관계형 데이터베이스를 연결해 주는 기술이다. 예를 들어 DB의 `member` 테이블과 코드의 `Member` 클래스를 매핑하면, 개발자는 매번 `SELECT * FROM member` 같은 SQL을 직접 작성하지 않고도 객체를 다루듯 데이터를 저장하고 조회할 수 있다.

```java
@Entity
@Table(name = "member")
public class Member {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;
}
```

```java
// SQL을 직접 쓰지 않고 객체로 저장하고 조회
memberRepository.save(member);
Member found = memberRepository.findById(1L).orElseThrow();
```

JPA가 주는 장점은 분명하다. 반복적인 코드를 줄여 주고, 다음과 같은 실수도 줄여 준다.

1. **타입 안정성**: 컬럼과 객체 필드가 타입으로 연결되기 때문에 많은 실수를 컴파일 시점에 발견할 수 있다.
2. **SQL 인젝션 위험 감소**: 파라미터 바인딩을 JPA가 처리해 준다.
3. **기본 CRUD 코드 감소**: 반복적인 `INSERT`, `UPDATE`, `SELECT` 코드를 직접 작성하지 않아도 된다.
4. **스키마 변경 관리**: 마이그레이션 도구와 함께 사용하면 변경 이력을 추적하기 쉽다.

특히 코드 우선(Code First) 방식으로 개발할 때 JPA는 꽤 좋은 선택이 될 수 있다. 여기까지는 ORM의 장점이 명확하다.

### 2. 우려되는 ORM의 부가 기능

문제는 그다음부터다. ORM의 핵심 기능인 매핑, 변경 감지(Dirty Checking), 지연 로딩(Lazy Loading), 마이그레이션(Migration)은 이미 오래전부터 사용되어 온 기능이다. 그래서 최근에 추가되는 기능 중에는 "이것도 된다", "저것도 된다"에 가까운 부가 기능이 많다.

예를 들면 다음과 같은 기능들이다.

1. 메모리 객체를 저장하면 여러 테이블에 알아서 나누어 저장하는 기능
2. JSON 문서를 객체 필드와 자동으로 매핑하는 기능
3. 다대다 관계의 조인 테이블을 자동으로 생성하는 기능

JPA와 Hibernate에도 이런 기능이 있다. 예를 들어 다대다 관계를 사용하면 조인 테이블을 직접 정의하지 않아도 Hibernate가 중간 테이블을 만들어 준다.

```java
@Entity
public class Student {
    @Id @GeneratedValue
    private Long id;

    // 개발자가 조인 테이블을 직접 만들지 않아도 Hibernate가 자동 생성
    @ManyToMany
    private List<Course> courses = new ArrayList<>();
}
```

위 코드는 `student_courses` 같은 조인 테이블을 자동으로 만들어 준다. JSON 컬럼 자동 매핑도 가능하다.

```java
@Entity
public class Product {
    @Id @GeneratedValue
    private Long id;

    // 객체를 JSON 컬럼에 자동으로 직렬화하고 역직렬화
    @JdbcTypeCode(SqlTypes.JSON)
    private ProductOptions options;
}
```

기술적으로는 편하고 좋아 보인다. 하지만 여기서 던져야 할 질문은 이것이다.

> 이 기능이 정말 필요한가?

단순히 "어, 되네" 하고 넘어가면 위험하다. 편하다는 이유만으로 기능을 사용하면, 나중에는 내가 어떤 DB 구조를 만들었는지 제대로 설명하지 못하는 상황이 생길 수 있다.

### 3. ORM이 가장 적합할 때: 테이블과 엔티티의 1대1 매핑

ORM이 가장 빛나는 순간은 DB 테이블 구조를 코드에 그대로 매핑할 때다. `member` 테이블은 `Member` 엔티티로, `orders` 테이블은 `Order` 엔티티로 대응시키는 방식이다.

```java
// member 테이블 <-> Member 엔티티
@Entity
@Table(name = "member")
public class Member {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
}

// orders 테이블 <-> Order 엔티티
@Entity
@Table(name = "orders")
public class Order {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 외래 키를 그대로 반영
    @Column(name = "member_id")
    private Long memberId;
}
```

이 구조에서는 테이블을 보면 엔티티가 보이고, 엔티티를 보면 테이블이 보인다. DB 구조가 코드에 투명하게 드러난다.

반대로 조인 테이블 자동 생성 같은 기능을 무심코 사용하면 DB 구조가 코드에서 잘 보이지 않게 된다. 자동 생성된 조인 테이블의 컬럼명, 인덱스, 제약 조건이 코드에 명확히 드러나지 않으면, DB를 직접 확인하기 전까지는 실제 구조를 알기 어렵다.

물론 구조를 충분히 이해하고 책임질 수 있다면 사용할 수 있다. 다만 "편해서 쓰는 것"과 "이해하고 쓰는 것"은 전혀 다르다.

---

## ORM의 선을 넘었을 때 생기는 문제와 해결

### 문제: 선을 넘으면 DB 설계 감각이 흐려진다

가장 큰 문제는 DB 설계 감각이 흐려진다는 점이다. 요즘 ORM은 객체 하나를 저장하면 연관된 여러 객체를 따라가며 여러 테이블에 데이터를 넣어 주기도 하고, JSON 컬럼에 객체를 넣어 주기도 한다. JPA에서 `cascade`와 연관관계를 깊게 걸어 두면 다음과 같은 코드가 가능해진다.

```java
@Entity
public class Order {
    @Id @GeneratedValue
    private Long id;

    // 저장 한 번에 연관된 모든 것이 함께 저장됨
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @OneToOne(cascade = CascadeType.ALL)
    private Delivery delivery;
}
```

```java
// save 한 번에 order, order_item 여러 건, delivery까지 전부 INSERT
orderRepository.save(order);
```

겉으로 보면 편하다. 하지만 바로 이 편리함 때문에 DB를 어떻게 설계했는지, 어떤 테이블에 어떤 순서로 데이터가 저장되는지, 어떤 쿼리가 실행되는지 놓치기 쉬워진다.

관계형 DB와 객체지향은 사고의 중심이 다르다.

| 구분 | 관계형 DB의 사고 | 객체 중심의 사고 |
| --- | --- | --- |
| 설계 중심 | 정규화, 인덱스, 조인 전략, 실행 계획 | 객체 그래프, 참조, 캡슐화 |
| 데이터 연결 | 외래 키와 조인 | 객체 참조 |
| 성능의 관건 | 어떤 쿼리가 어떻게 실행되는가 | 객체를 얼마나 편하게 다루는가 |

객체 중심의 사고만으로 DB를 다루기 시작하면, 나중에 "왜 이렇게 느리지?"라는 질문을 마주하게 된다. 그때서야 SQL 로그를 켜고 실행 계획을 확인한다. 처음부터 DB 관점으로 설계했다면 피할 수 있었던 문제를, 객체 추상화에 가려진 채 뒤늦게 발견하는 셈이다.

MySQL 8.4에서 실행 계획을 확인하는 코드는 다음과 같다.

```sql
-- 느려진 쿼리의 실행 계획을 확인하는 상황
EXPLAIN ANALYZE
SELECT * FROM orders o
JOIN order_item oi ON oi.order_id = o.id
WHERE o.member_id = 42;
```

실행 계획을 확인하는 것 자체가 나쁜 것은 아니다. 오히려 꼭 해야 하는 일이다. 문제는 처음부터 DB 관점을 고려하지 않고 있다가, 성능 문제가 터진 뒤에야 이 작업을 하게 된다는 점이다.

### 해결: 엔티티는 DB 구조 그대로, 복합 객체는 별도 레이어에서 조합

권장하는 방식은 단순하다.

1. JPA 엔티티는 DB 구조를 그대로 반영한다. 테이블 하나에 엔티티 하나가 대응하도록 최대한 단순하게 유지한다.
2. 비즈니스 로직이나 화면에서 필요한 복합 객체는 엔티티가 아니라 서비스 레이어나 DTO에서 조합한다.

즉, DB에서는 필요한 데이터를 읽어 오고, 애플리케이션 레이어에서 그 데이터를 조합해 최종 응답 형태를 만든다.

```java
// 엔티티는 DB 구조를 그대로 반영
@Entity
@Table(name = "orders")
public class Order {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "total_price")
    private int totalPrice;
}
```

화면이나 비즈니스 로직에 필요한 복합 객체는 DTO로 따로 정의한다.

```java
// 비즈니스 로직과 화면에 필요한 복합 객체는 별도 DTO로 정의
public class OrderDetailDto {
    private Long orderId;
    private String memberName;
    private int totalPrice;
    private List<OrderItemDto> items;

    public OrderDetailDto(Long orderId, String memberName,
                          int totalPrice, List<OrderItemDto> items) {
        this.orderId = orderId;
        this.memberName = memberName;
        this.totalPrice = totalPrice;
        this.items = items;
    }
    // getter 생략
}
```

그리고 서비스 레이어에서 필요한 데이터를 읽어 와 조합한다.

```java
@Service
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final MemberRepository memberRepository;
    private final OrderItemRepository orderItemRepository;

    public OrderQueryService(OrderRepository orderRepository,
                             MemberRepository memberRepository,
                             OrderItemRepository orderItemRepository) {
        this.orderRepository = orderRepository;
        this.memberRepository = memberRepository;
        this.orderItemRepository = orderItemRepository;
    }

    @Transactional(readOnly = true)
    public OrderDetailDto getOrderDetail(Long orderId) {
        // DB에서 필요한 데이터만 읽어 옴
        Order order = orderRepository.findById(orderId).orElseThrow();
        Member member = memberRepository.findById(order.getMemberId()).orElseThrow();
        List<OrderItemDto> items = orderItemRepository.findItemDtosByOrderId(orderId);

        // 읽어 온 데이터를 조합해 최종 복합 객체로 반환
        return new OrderDetailDto(
                order.getId(),
                member.getName(),
                order.getTotalPrice(),
                items
        );
    }
}
```

복잡한 조회는 DTO로 직접 프로젝션해서 필요한 컬럼만 가져오는 편이 좋다.

```java
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    // 필요한 컬럼만 DTO로 직접 조회
    @Query("SELECT new com.example.dto.OrderItemDto(oi.name, oi.price, oi.count) " +
           "FROM OrderItem oi WHERE oi.orderId = :orderId")
    List<OrderItemDto> findItemDtosByOrderId(@Param("orderId") Long orderId);
}
```

이 방식은 DTO가 늘어나고 코드도 조금 길어진다. 손이 더 가는 것도 사실이다. 하지만 코드를 짧게 유지하려고 서버 성능과 DB 구조의 명확성을 포기하는 것보다는 낫다. 자동 매핑에 모든 것을 맡기면, 그 편리함의 대가를 성능 문제와 유지보수 비용으로 치르게 된다.

### 결론: JPA는 날것의 SQL을 줄이기 위한 도구

정리하면 JPA를 쓰는 목적은 다음 정도로 생각하는 것이 좋다.

1. 반복적인 SQL 작성을 줄이기 위해
2. 파라미터 바인딩을 안전하게 처리하기 위해
3. 타입 안정성을 가지고 데이터를 읽고 쓰기 위해
4. DB 테이블과 엔티티의 기본 매핑을 편하게 관리하기 위해

그 이상의 기능, 예를 들어 메모리 객체 자동 분해, 깊은 `cascade` 저장, 조인 테이블 자동 생성, JSON 자동 매핑 같은 기능은 조심해서 다뤄야 한다. 쓰지 말라는 뜻은 아니다. 다만 그 기능이 만들어 내는 DB 구조와 실행 쿼리를 정확히 이해하고 책임질 수 있을 때 사용해야 한다.

가장 중요한 원칙은 이것이다.

> DB는 관계형이지 객체지향이 아니다. 객체지향적 환상을 DB에 강요하지 말자.

이 선을 지키면 JPA는 좋은 도구다. 하지만 이 선을 넘으면, 편리했던 도구가 오히려 문제를 숨기는 장치가 될 수 있다.
