# 베이스 엔티티 리뷰 가이드

이 문서는 JPA 엔티티가 공유하는 베이스 클래스 계층 규칙을 코드 리뷰 점검 항목으로 정리한 가이드다.
`@Entity` 또는 `@MappedSuperclass`가 붙은 클래스가 추가되거나 변경되는 PR에 적용한다.

기준 스택은 Java, Spring Data JPA, MySQL 8.4, Lombok이다.
각 항목이 왜 필요한지는 [base-entity-rationale.md](./base-entity-rationale.md)를 참고한다.
엔티티의 인스턴스 생성 방식은 [entity-creation-guideline.md](./entity-creation-guideline.md)가 다루며, 이 문서는 그 엔티티들이 공통으로 물려받는 뼈대만 본다.

## 1. 계층 구조

> **`BasePublic*` 둘은 지금 쓰지 않는다.** 외부 노출 식별자를 추후 고려하기로 했고, 그 전까지 실제 엔티티는 아래 둘 중 하나를 상속한다.
> 판단 기준과 도입 방법은 [identifier-strategy-guideline.md](./identifier-strategy-guideline.md)에 있다.

모든 실제 엔티티는 아래 넷 중 하나를 상속한다. **상속하면 안 되는 중간 계층은 두지 않는다.**

| 클래스 | PK | created_at | updated_at | public_id | 상속 대상 |
|--------|:--:|:----------:|:----------:|:---------:|-----------|
| `BaseImmutableTimeEntity` | Long | O | X | X | 이력, 로그 (append-only) |
| `BaseMutableTimeEntity` | Long | O | O | X | 일반 도메인 엔티티, 코드 테이블 |
| `BasePublicImmutableTimeEntity` | Long | O | X | O | 외부에 노출되는 이력 (**추후**) |
| `BasePublicMutableTimeEntity` | Long | O | O | O | 외부에 노출되는 도메인 엔티티 (**추후**) |

축이 둘이다. **수정 가능 여부**와 **외부 노출 여부**다.

```
                    수정 불가                     수정 가능
외부 미노출   BaseImmutableTimeEntity        BaseMutableTimeEntity
외부 노출     BasePublicImmutableTimeEntity  BasePublicMutableTimeEntity
```

`public_id`를 다는 기준은 `identifier-strategy-guideline.md` 2절이 정한다.
**실무상 애그리거트 루트가 그 조건에 대응하며**, 하위 엔티티와 이력 테이블은 대개 `Public`이 없는 쪽이다.

점검 항목
* `BE-1-01` 모든 `@Entity`가 위 네 클래스 중 하나를 상속하는가
* `BE-1-02` 수정되지 않는 테이블(이력, 로그)에 `Mutable` 베이스를 쓰지 않았는가
  `updated_at`이 영원히 `created_at`과 같은 값으로 남아 컬럼과 인덱스가 낭비된다.
* `BE-1-03` `public_id`를 엔티티가 직접 선언하지 않고 `BasePublic*` 상속으로 얻는가
  직접 선언하면 컬럼명과 타입이 갈려 공통 매핑이 무너진다. `IDS-6-01`이 요구하는 `BINARY(16) NOT NULL`을 엔티티마다 지켜야 한다.
* `BE-1-04` 외부에 노출되지 않는 엔티티가 `BasePublic*`을 상속하지 않았는가
  UNIQUE 인덱스 하나는 저장 공간과 삽입 비용을 모두 늘린다. 쓰이지 않으면 순손실이다.
* `BE-1-05` `id`와 시각 컬럼을 엔티티가 직접 선언하지 않았는가

```java
// 점검 대상: 베이스를 상속하지 않고 id 와 시각을 직접 선언
@Entity
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private LocalDateTime createdAt;
}

// 개선: 외부에 노출되지 않는 엔티티
@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem extends BaseMutableTimeEntity {
    private int quantity;
}

// 개선: 외부에 노출되는 애그리거트 루트
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BasePublicMutableTimeEntity {
    private Long memberId;

    private Order(Long memberId) {
        this.memberId = memberId;   // public_id 는 다루지 않는다. ORM 이 INSERT 직전에 채운다
    }

    public static Order place(Long memberId) {
        return new Order(memberId);
    }
}
```

### 1.1 `public_id`는 베이스가 `UUID`로 든다 (추후)

**아래는 도입할 때의 형태이며 지금 코드에는 없다.**

**타입 래퍼를 두지 않는다.** 엔티티마다 `OrderPublicId` 같은 값 객체를 만들지 않고 `UUID`를 그대로 쓴다.
근거는 `identifier-strategy-rationale.md`에 있다.

**변환기도 두지 않는다.** Hibernate가 MySQL에서 `UUID`를 `binary(16)`으로 매핑하므로 손으로 옮길 것이 없다.

```java
@MappedSuperclass
@Getter
public abstract class BasePublicMutableTimeEntity extends BaseMutableTimeEntity {

    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)   // ORM 이 INSERT 직전에 채운다
    @Column(name = "public_id", nullable = false, updatable = false,
            columnDefinition = "BINARY(16)")
    private UUID publicId;
}
```

`columnDefinition`은 매핑을 바꾸려는 것이 아니라 **의도를 명시하는 것**이다. 빼도 같은 컬럼이 나온다.

**베이스에 생성자가 없다.** 하위 엔티티는 `public_id`를 다루지 않고, 값은 `save()` 시점에 채워진다.
생성 방식은 `identifier-strategy-guideline.md` 5절이 정한다.

점검 항목
* `BE-1-06` 베이스가 `UUID`를 들고 타입 래퍼를 만들지 않았는가
  `AttributeConverter`도 새로 만들지 않는다. Hibernate 기본 매핑이 `binary(16)`이다.
* `BE-1-07` 하위 엔티티의 생성자나 팩터리가 `public_id`를 인자로 받지 않는가
  ORM 이 채우므로 받을 이유가 없다. 받아도 INSERT 때 덮어쓰이므로 값이 조용히 사라진다.
  v4 예외 대상 테이블은 `style = RANDOM`으로 바꾸고 사유를 남긴다(`IDS-3-01`).

## 2. PK 규칙

점검 항목
* `BE-2-01` PK가 `Long`(MySQL `BIGINT`)인가
* `BE-2-02` `@GeneratedValue(strategy = GenerationType.IDENTITY)`를 쓰는가
* `BE-2-03` 코드 테이블도 정수 PK를 쓰는가
* `BE-2-04` 문자열 PK를 쓴다면 5절의 예외 조건과 사유가 PR에 있는가

## 3. 시각 컬럼과 Auditing

생성과 수정 시각은 JPA Auditing이 채운다. 코드에서 직접 넣지 않는다.

점검 항목
* `BE-3-01` 생성 시각을 `LocalDateTime.now()`로 직접 대입하지 않는가
* `BE-3-02` `created_at`에 `updatable = false`가 지정되어 있는가
* `BE-3-03` 설정 클래스에 `@EnableJpaAuditing`이 한 번 선언되어 있는가
  선언이 없으면 `@CreatedDate`가 조용히 동작하지 않아 시각이 `null`로 저장된다.
* `BE-3-04` `@EntityListeners(AuditingEntityListener.class)`를 하위 클래스에 중복 선언하지 않았는가

```java
// 점검 대상: 시각을 코드에서 직접 채움
this.createdAt = LocalDateTime.now();

// 개선: 베이스의 Auditing 에 맡긴다
@CreatedDate
@Column(name = "created_at", nullable = false, updatable = false)
private LocalDateTime createdAt;
```

## 4. 코드 테이블

후보값이 정해진 속성의 기본은 enum이다. 운영자가 값 목록을 화면에서 관리해야 하는 경우에만 테이블로 둔다.
판단 기준은 [entity-creation-guideline.md](./entity-creation-guideline.md) 4장을 따른다.

**상위 분류나 노출 순서 같은 속성이 딸린 것은 코드 테이블이 아니라 일반 도메인 엔티티다.** 상품 카테고리가 그렇다.

점검 항목
* `BE-4-01` 테이블로 만들 근거(운영자의 런타임 관리 요구)가 있는가
  근거가 없으면 enum이어야 한다.
* `BE-4-02` 코드 테이블이 `BaseMutableTimeEntity`를 상속하는가
* `BE-4-03` `code` 컬럼에 UNIQUE 제약이 있는가
* `BE-4-04` 코드값 조회 경로(`findByCode`)와 캐싱이 준비되어 있는가

```java
/* 개선: 정수 PK + code UNIQUE.
   운영자가 CS 화면에서 사유 목록을 늘리고 문구를 고치는 대상이라 테이블로 둔다.
   코드로 분기하는 로직이 없어 값이 늘어도 배포가 필요 없다. */
@Entity
@Table(name = "claim_reason",
    uniqueConstraints = @UniqueConstraint(name = "uk_claim_reason_code", columnNames = "code"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClaimReason extends BaseMutableTimeEntity {

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 50)
    private String name;
}
```

**회원 등급을 예로 들지 않는다.** 할인율과 승급 기준이 딸리는 순간 코드 테이블이 아니라 도메인 엔티티다.
판별 기준은 `entity-creation-guideline.md` 4장에 있다.

## 5. 문자열 PK 예외

문자열 코드를 PK로 두는 것은 표준이 아니라 예외다. 아래 세 조건이 **모두** 성립할 때만 허용한다.

1. 운영자가 값 목록을 관리해야 한다 (그래서 enum이 아니라 테이블)
2. 그 값이 대량으로 참조된다 (그래서 조인 비용이 실제 문제가 된다)
3. 캐싱으로도 조인을 없애기 어렵다

점검 항목
* `BE-5-01` 세 조건이 모두 성립하는가
  사유 코드 같은 소형 코드 테이블은 2번이 맞지 않아 해당하지 않는다.
* `BE-5-02` 베이스 미상속 사유가 PR에 기재되어 있는가
* `BE-5-03` 시각 컬럼을 직접 선언했는가
  베이스를 상속하지 않으므로 `created_at`과 `updated_at`이 자동으로 생기지 않는다.

## 6. 상속 선택 기준

두 가지를 순서대로 묻는다.

```
1. 이 테이블의 행이 수정되는가?
   아니오 -> Immutable      예 -> Mutable

2. 식별자가 외부(API 경로, 응답 본문, 타 서비스)에 노출되는가?
   아니오 -> 그대로          예 -> Public 을 붙인다
```

| 테이블 성격 | 상속 대상 |
|-------------|-----------|
| 애그리거트 루트 (회원, 주문, 상품) | `BasePublicMutableTimeEntity` |
| 부가 속성이 딸린 분류 (카테고리, 회원 등급, 공급처) | `BaseMutableTimeEntity` (코드 테이블이 아니다) |
| 하위 엔티티 중 부모 안에서 안정적으로 지목되는 것 (주문 항목, 장바구니 항목) | `BaseMutableTimeEntity` |
| 하위 엔티티 중 그렇지 않은 것 (배송지, 상품 옵션) | `BasePublicMutableTimeEntity` |
| 운영 관리 코드 테이블 (사유 코드 등) | `BaseMutableTimeEntity` (id + code UNIQUE) |
| 이력, 로그, append-only | `BaseImmutableTimeEntity` |
| 외부에 단건으로 노출되는 이력 | `BasePublicImmutableTimeEntity` |
| 후보값이 정해진 속성 중 운영 관리가 불필요한 것 | 테이블을 만들지 않고 enum |
| 대량 참조 + 캐싱 곤란한 특수 코드 테이블 | 문자열 PK 예외 (베이스 미상속, 사유 기재) |

**하위 엔티티라고 자동으로 `Public`이 빠지는 것이 아니다.** 부모 안에서 안정적으로 지목할 수단이 있는지를 본다.

```
주문 항목    (주문, 옵션) 자연키가 있고 확정 후 안 바뀐다        Public 없음
배송지       자연키가 없고 수시로 지우고 고쳐 순번이 밀린다      Public 붙인다
```

`/orders/{id}/items/3` 이 성립하는 이유는 주문 항목이 확정 후 불변이기 때문이다.
가변 목록에 순번을 쓰면 하나를 지우는 순간 뒤 번호가 밀려 클라이언트가 다른 행을 가리킨다.

이력 테이블도 목록으로만 조회되면 단건 참조 대상이 아니다. 판단 기준표는 `identifier-strategy-guideline.md` 2절에 있다.

## 7. 참고

- Spring Data JPA Auditing: https://docs.spring.io/spring-data/jpa/reference/auditing.html
- Hibernate ORM User Guide: https://docs.jboss.org/hibernate/orm/6.4/userguide/html_single/Hibernate_User_Guide.html
