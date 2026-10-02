# JPA 사용 리뷰 가이드 (RDB 관점 유지)

이 문서는 ORM(JPA)을 쓰되 관계형 DB의 관점을 잃지 않기 위한 점검 항목을 정리한 가이드다.
JPA 엔티티, 연관관계, 조회 코드가 포함되거나 변경되는 PR에 적용한다.

이 가이드의 점검 항목은 근거 문서 [jpa-rdb-rationale.md](./jpa-rdb-rationale.md)에서 코드 리뷰에 적용할 부분만 추출해 정리한 것이다.
각 항목의 배경과 상세한 설명은 근거 문서를 참고한다.

핵심 원칙은 하나다. DB는 관계형 모델을 기반으로 동작하므로, 객체지향적인 사고를 그대로 DB에 밀어 넣지 않는다.
JPA는 정해진 용도 안에서 쓸 때 좋은 도구이고, 그 선을 넘으면 문제를 숨기는 장치가 된다.

기준 스택은 Java, Spring Data JPA, MySQL 8.4이다.
이 가이드는 database.md(N+1, 인덱스, 트랜잭션)와 함께 본다. database.md가 쿼리와 트랜잭션의 정확성을 본다면, 이 문서는 엔티티 설계가 DB 구조를 투명하게 반영하는지를 본다.

## 1. 엔티티와 테이블의 1대1 매핑

ORM이 가장 빛나는 순간은 DB 테이블 구조를 코드에 그대로 매핑할 때다.

점검 항목
* `JPA-1-01` 테이블 하나에 엔티티 하나가 대응하도록 최대한 단순하게 유지했는가
  테이블을 보면 엔티티가 보이고, 엔티티를 보면 테이블이 보여야 한다.
* `JPA-1-02` 외래 키를 연관 객체 매핑 대신 식별자 컬럼으로 그대로 반영하는 선택을 검토했는가
* `JPA-1-03` 비즈니스 로직이나 화면에 필요한 복합 객체를 엔티티에 넣지 않고 서비스 레이어나 DTO에서 조합했는가

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

    @Column(name = "member_id")
    private Long memberId;   // 외래 키를 그대로 반영
}
```

## 2. DB 구조를 가리는 부가 기능

매핑, 변경 감지, 지연 로딩, 마이그레이션이라는 핵심 기능 위에 얹힌 부가 기능은 DB 구조를 코드에서 보이지 않게 만든다.
"이 기능이 정말 필요한가"를 먼저 묻는다.

점검 항목
* `JPA-2-01` `@ManyToMany`로 조인 테이블을 자동 생성하지 않는가
  자동 생성된 조인 테이블의 컬럼명, 인덱스, 제약 조건이 코드에 드러나지 않으면 DB를 직접 확인하기 전까지 구조를 알 수 없다.
* `JPA-2-02` 객체를 JSON 컬럼에 자동 매핑(`@JdbcTypeCode(SqlTypes.JSON)` 등)할 때, 그 컬럼 구조와 조회 방식을 이해하고 책임질 수 있는가
* `JPA-2-03` "편해서 쓰는 것"과 "이해하고 쓰는 것"을 구분했는가
  기능 자체가 금지는 아니다. 만들어지는 DB 구조와 실행 쿼리를 정확히 이해하고 책임질 수 있을 때만 쓴다.

```java
// 점검 대상: 조인 테이블(student_courses)이 자동 생성되어 구조가 코드에서 보이지 않음
@Entity
public class Student {
    @Id @GeneratedValue
    private Long id;

    @ManyToMany
    private List<Course> courses = new ArrayList<>();
}
```

```java
// 점검 대상: 객체를 JSON 컬럼에 자동 직렬화/역직렬화. 컬럼 구조와 조회 방식을 이해하고 있는가
@Entity
public class Product {
    @Id @GeneratedValue
    private Long id;

    @JdbcTypeCode(SqlTypes.JSON)
    private ProductOptions options;
}
```

```java
// 개선: 조인 엔티티를 명시해 컬럼과 제약을 코드에 드러냄
@Entity
@Table(name = "student_course")
public class StudentCourse {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id")
    private Long studentId;

    @Column(name = "course_id")
    private Long courseId;
}
```

## 3. cascade와 연관관계로 인한 숨은 쓰기

`cascade`와 연관관계를 깊게 걸어 두면 저장 한 번에 연관된 모든 것이 함께 저장된다.
겉으로는 편하지만, 어떤 테이블에 어떤 순서로 저장되는지와 어떤 쿼리가 실행되는지를 놓치기 쉬워진다.

점검 항목
* `JPA-3-01` `cascade = CascadeType.ALL`과 깊은 연관관계로 save 한 번에 여러 테이블이 묶여 쓰이지 않는가
  무엇이 몇 건 INSERT되는지 호출부에서 드러나야 한다.
* `JPA-3-02` 연관 객체의 저장을 의도적으로 분리해, 각 쓰기가 어떤 테이블에 일어나는지 추적 가능한가

```java
// 점검 대상: save 한 번에 order, order_item 여러 건, delivery까지 전부 INSERT
@Entity
public class Order {
    @Id @GeneratedValue
    private Long id;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @OneToOne(cascade = CascadeType.ALL)
    private Delivery delivery;
}
```

```java
// 저장 한 번에 여러 테이블이 쓰이지만, 이 호출만 봐서는 무엇이 몇 건 쓰이는지 알 수 없다
orderRepository.save(order);
```

관계형 DB는 정규화, 인덱스, 조인 전략, 실행 계획을 중심에 두고 성능의 관건이 "어떤 쿼리가 어떻게 실행되는가"인 반면, 객체 중심 사고는 객체 그래프와 참조를 중심에 두고 "객체를 얼마나 편하게 다루는가"를 본다.
객체 중심 사고만으로 DB를 다루면 나중에 느려진 뒤에야 실행 계획을 확인하게 된다.

```sql
-- MySQL 8.4에서 느려진 쿼리의 실행 계획을 확인하는 상황
EXPLAIN ANALYZE
SELECT * FROM orders o
JOIN order_item oi ON oi.order_id = o.id
WHERE o.member_id = 42;
```

실행 계획 확인 자체는 꼭 해야 하는 일이다. 문제는 처음부터 DB 관점을 고려하지 않다가 성능 문제가 터진 뒤에야 하게 된다는 점이다.

## 4. 조회는 DB 관점으로, 복합 객체는 별도 레이어에서 조합

DB에서는 필요한 데이터를 읽어 오고, 애플리케이션 레이어에서 그 데이터를 조합해 최종 응답 형태를 만든다.

점검 항목
* `JPA-4-01` 복합 객체를 엔티티에 넣지 않고 별도 DTO로 정의했는가
* `JPA-4-02` 화면용 복합 객체를 서비스 레이어에서 조합하고, 엔티티는 데이터만 읽어 오는 역할에 두는가
* `JPA-4-03` 복잡한 조회를 엔티티 전체 로딩과 객체 그래프 탐색으로 풀지 않고, 필요한 컬럼만 DTO로 프로젝션하는가
* `JPA-4-04` 성능 문제를 사후에 실행 계획으로 수습하기 전에, 설계 시점에 쿼리 형태를 고려했는가
  (N+1, 인덱스 활용 등 쿼리 차원의 점검은 database.md를 함께 본다.)

```java
// 비즈니스 로직과 화면에 필요한 복합 객체는 별도 DTO로 정의
public class OrderDetailDto {
    private Long orderId;
    private String memberName;
    private int totalPrice;
    private List<OrderItemDto> items;
    // 생성자, getter 생략
}
```

```java
// 서비스 레이어에서 필요한 데이터를 읽어 와 조합
@Transactional(readOnly = true)
public OrderDetailDto getOrderDetail(Long orderId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    Member member = memberRepository.findById(order.getMemberId()).orElseThrow();
    List<OrderItemDto> items = orderItemRepository.findItemDtosByOrderId(orderId);

    return new OrderDetailDto(order.getId(), member.getName(),
                              order.getTotalPrice(), items);
}
```

```java
// 복잡한 조회는 필요한 컬럼만 DTO로 직접 프로젝션
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    @Query("SELECT new com.example.dto.OrderItemDto(oi.name, oi.price, oi.count) " +
           "FROM OrderItem oi WHERE oi.orderId = :orderId")
    List<OrderItemDto> findItemDtosByOrderId(@Param("orderId") Long orderId);
}
```

점검 항목
* `JPA-4-05` DTO가 늘고 코드가 길어지는 것을 감수하더라도, 자동 매핑에 맡겨 성능과 DB 구조의 명확성을 잃는 쪽을 택하지 않았는가
  편리함의 대가는 성능 문제와 유지보수 비용으로 돌아온다.

## 5. JPA 사용 범위

JPA를 쓰는 목적을 명확히 한정한다.

점검 항목
* `JPA-5-01` JPA를 다음 목적 안에서 쓰는가
  반복적인 SQL 작성을 줄이기 위해, 파라미터 바인딩을 안전하게 처리하기 위해, 타입 안정성을 가지고 데이터를 읽고 쓰기 위해, DB 테이블과 엔티티의 기본 매핑을 편하게 관리하기 위해.
* `JPA-5-02` 메모리 객체 자동 분해, 깊은 `cascade` 저장, 조인 테이블 자동 생성, JSON 자동 매핑 같은 기능은, 그것이 만들어 내는 DB 구조와 실행 쿼리를 정확히 이해하고 책임질 수 있을 때만 쓰는가

> DB는 관계형이지 객체지향이 아니다. 객체지향적 환상을 DB에 강요하지 말자.

이 선을 지키면 JPA는 좋은 도구다. 하지만 이 선을 넘으면, 편리했던 도구가 오히려 문제를 숨기는 장치가 될 수 있다.
