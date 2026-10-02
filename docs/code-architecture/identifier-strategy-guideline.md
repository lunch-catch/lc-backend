# 식별자 전략 리뷰 가이드

이 문서는 식별자 설계 규칙을 코드 리뷰 점검 항목으로 정리한 가이드다.
엔티티, 스키마, API 경로와 응답 DTO에 식별자가 추가되거나 변경되는 PR에 적용한다.

> **외부 노출 식별자는 지금 적용하지 않는다. 추후 고려한다.**
> API 가 설계되지 않아 2.1절의 질문에 답할 수 없고, 답 없이 넣으면 이 문서가 금지하는 추정이 된다.
> 이 문서는 그때를 위한 설계와 판단 기준을 담는다. 현재 스키마에는 `public_id` 컬럼이 없다.
>
> 도입할 때는 기존 마이그레이션을 고치지 않고 **추가만 하는 마이그레이션**으로 컬럼과 UNIQUE 를 얹는다.

기준 스택은 Java, Spring, MySQL 8.4(InnoDB)이다.
각 항목이 왜 필요한지는 [identifier-strategy-rationale.md](./identifier-strategy-rationale.md)를 참고한다.
엔티티 PK의 베이스 규칙은 [base-entity-guideline.md](./base-entity-guideline.md)를 따르며, 이 문서는 그 위에 외부 노출 식별자를 얹을지와 어떻게 얹을지를 다룬다.

## 1. 세 가지 식별자 층

| 층 | 소비자 | 요구 조건 | 대표 타입 |
|---|---|---|---|
| 내부 | DB 엔진, 조인, FK | 작을 것, 순차적일 것 | `BIGINT UNSIGNED` |
| 외부 | 클라이언트, 타 서비스 | 추측 불가, 사전 채번 | UUID |
| 비즈니스 | 사람 (CS, 고객) | 짧고 읽을 수 있을 것 | 규칙 있는 문자열 |

세 층이 항상 다 필요하지는 않다. 필요한 층만 만들되 **하나의 컬럼이 두 층을 겸하게 하지 않는다.**

점검 항목
* `IDS-1-01` 하나의 컬럼이 두 층의 역할을 겸하지 않는가
  짧고 읽기 쉬우면 추측 가능하고, 추측 불가능하면 전화로 부를 수 없다. 요구 조건이 서로 모순된다.
* `IDS-1-02` 리소스가 여러 식별자를 가진다면 각각의 소비자와 용도가 문서에 명시되어 있는가

## 2. 구성 선택

```
1. 외부(클라이언트 API, 타 서비스, 외부 시스템)에 식별자가 노출되는가?
   아니오 -> 순수 BIGINT. 여기서 끝.
   예    -> 2번으로

2. 대량 벌크 INSERT 가 핵심 요구사항인가?
   예    -> 순수 UUID PK. IDENTITY 는 생성 키를 받아야 해서 JDBC 배칭이 꺼진다.
   아니오 -> 3번으로

3. 테이블당 세컨더리 인덱스가 많은가? (대략 3개 이상)
   예    -> 하이브리드. PK 가 모든 인덱스에 복사된다.
   아니오 -> 순수 UUID PK 도 무방.
```

점검 항목
* `IDS-2-01` 구성 선택의 근거가 기록되어 있는가
* `IDS-2-02` API 가 단독으로 지목하는데 `public_id`를 누락하지 않았는가
* `IDS-2-03` 반대로 지목하지 않는데 습관적으로 달지 않았는가
  UNIQUE 인덱스 하나는 저장 공간과 삽입 비용을 모두 늘린다. 쓰이지 않으면 순손실이다.
* `IDS-2-04` 새 테이블의 판단 결과가 기록되어 있는가
  기록이 없으면 다음 사람이 같은 판단을 다시 하게 되고, 그때 답이 갈린다.
* `IDS-2-05` API 가 정해지지 않았는데 확정으로 적지 않았는가
  2.2절의 두 조건에 걸리지 않으면 잠정이다.

### 2.1 판단은 질문 하나로 한다

**API 가 이 행 하나를 단독으로 지목하는가?**

```
예    -> public_id 를 단다
아니오 -> 달지 않는다
```

단독 지목이란 URL 경로나 요청 본문에 그 행만을 가리키는 값이 들어간다는 뜻이다.
부모와 자연키의 조합으로 도달하면 단독 지목이 아니다.

```
DELETE /v1/parents/{parent}/children/{child}   단독 지목이다
DELETE /v1/parents/{parent}/children  {ref}    ref 가 가리키는 다른 리소스를 지목한다
GET    /v1/parents/me                          지목이 없다. 주체당 하나라 경로에 값이 없다
```

외부 시스템(결제사, 물류사)과 식별자를 주고받는 것도 단독 지목으로 본다.

**계층은 기준이 아니다.** 하위 엔티티라도 API 가 단독으로 지목하면 단다.

### 2.2 API 없이 확정되는 경우는 둘뿐이다

| 조건 | 판정 |
|---|---|
| append-only 이력이라 목록으로만 조회한다 | X. API 가 어떻게 나오든 단건을 지목하지 않는다 |
| 부모와 **다른 리소스**의 조합에 UNIQUE 가 있다 | X. 그 조합이 곧 지목 수단이다 |

**부모 단독 UNIQUE 는 이 조건이 아니다.**

```
uk_child (parent_id, other_id)   부모 + 다른 리소스   확정 X. other 를 지목하면 도달한다
uk_child (parent_id)             부모 단독            잠정
```

부모 단독 UNIQUE 는 부모 밑에 중첩해 **도달할 수 있다**는 뜻이지, API 가 **그렇게 한다**는 뜻이 아니다.
독립 리소스로 노출하는 설계도 그만큼 자연스럽다. 그 선택은 API 가 한다.

**스키마 사실에서 API 설계를 추론하지 않는다.** 이 문서가 금지하는 것이 바로 그 추론이다.

**나머지는 API 설계 전까지 답할 수 없다.**

### 2.3 답할 수 없을 때 추정하지 않는다

스키마를 API 보다 먼저 만들면 답이 없는 테이블이 남는다. 그때 지레 정하지 않는다.

**추정은 사람마다 갈리고, 갈리면 판정이 뒤집힌다.**
뒤집히는 비용은 컬럼 하나가 아니라 그 컬럼을 참조하던 코드와 이미 나간 API 계약이다.

답이 없는 것은 **잠정으로 표시하고 API 설계에서 확정한다.**
잠정인 채로 두는 것이 틀린 값을 확정으로 적어 두는 것보다 낫다.

### 2.4 결정을 한곳에 기록한다

판단 결과를 테이블마다 기록하고, 새 테이블은 그 기록에 줄을 추가한다.
**기록이 없으면 다음 사람이 같은 판단을 처음부터 다시 하게 되고, 그때 답이 갈린다.**

기록에는 셋을 남긴다.

```
public_id 유무
확정인가 잠정인가
그렇게 정한 근거 (지목하는 API 또는 2.2절의 조건)
```

논의는 줄을 넣을 때 한 번만 하고, 그 뒤로는 아무도 다시 유도하지 않는다.

## 3. UUID 버전

**기본은 v7이다.** 해당 리소스가 응답 본문에 생성 시각을 이미 노출하고 있다면 v7을 쓴다.

아래에 해당하는 리소스만 v4를 쓴다.

* 생성 시각 자체가 민감 정보인 리소스 (제보, 인사 기록, 의료 기록, 감사 대상 문서)
* 응답 본문에 생성 시각을 포함하지 않기로 한 리소스
* 목록 응답에서 처리 순서나 배치 주기가 드러나면 곤란한 리소스

점검 항목
* `IDS-3-01` v4를 선택한 테이블에 사유가 기록되어 있는가
  사유가 없으면 v7이다. 기록이 없으면 다음 사람이 판단을 재현할 수 없다.
* `IDS-3-02` v1, v3, v5, v6, v8을 쓰지 않았는가
  v1과 v6은 MAC 주소가 박히고, v3과 v5는 난수가 0비트다. MySQL 내장 `UUID()`가 v1이다.
* `IDS-3-03` 외부 식별자로 조회할 때 소유권 또는 권한 검증을 함께 수행하는가
  추측 불가능성은 인가를 대체하지 않는다.
  일반적인 인가 판정은 `auth` 모듈의 역할 검사가 소유한다(기능 명세서 94행, 규칙 5). 이 항목은 그중 외부 식별자 경로에 한정한 재확인이다.

## 4. 난수원

**버전 선택보다 중요하다.** 난수원이 예측 가능하면 버전 논의 자체가 무의미해진다.

점검 항목
* `IDS-4-01` 난수원이 `SecureRandom`인가
  `java.util.Random`은 시드가 48비트뿐이라 몇 개의 출력만 관측해도 내부 상태가 복원된다. `ThreadLocalRandom`도 금지한다.
* `IDS-4-02` `SecureRandom.getInstanceStrong()`을 쓰지 않았는가
  리눅스에서 블로킹 엔트로피 소스에 연결될 수 있어 컨테이너 기동 직후 애플리케이션이 통째로 정지한다.
* `IDS-4-03` 외부 UUID 라이브러리를 도입했다면 난수원을 확인했는가
  일부 v7 구현체가 속도를 위해 rand_b를 비암호학적 PRNG로 채운다.
* `IDS-4-04` 단조 옵션을 쓴다면 증가값이 CSPRNG에서 나오는가
  RFC 9562가 경고하는 것은 증가값이 고정된 카운터(Method 1)다. 앞의 값을 보면 다음 값이 그대로 나온다.
  증가값을 난수로 뽑는 Method 2는 인접한 두 값 사이에 32비트 이상을 남기므로 허용한다.
* `IDS-4-05` 밀리초가 바뀔 때 난수 부분을 다시 뽑는가
  같은 시각 구간 안에서만 값이 이어져야 한다. 구간을 넘어서까지 이어지면 상관관계가 누적된다.

## 5. 생성 시점

**ORM 이 INSERT 직전에 채운다.** 서비스가 생성기를 호출하지 않는다.

**Hibernate 내장 `@UuidGenerator`를 쓴다.** 직접 만들지 않는다.

javadoc 문구는 식별자를 말하지만, 이 애노테이션은 `@IdGeneratorType`과 `@ValueGenerationType`을 함께 달고 있고
`GeneratorCreationContext`를 받는 생성자가 있어 **비식별자 필드에 그대로 붙는다.**

```java
@MappedSuperclass
public abstract class BasePublicMutableTimeEntity extends BaseMutableTimeEntity {

    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    // AttributeConverter 를 두지 않는다. Hibernate 가 MySQL 에서 UUID 를 binary(16) 으로 매핑한다
    @Column(name = "public_id", nullable = false, updatable = false,
            columnDefinition = "BINARY(16)")
    private UUID publicId;
}
```

**`style`을 생략하면 안 된다.** 기본값 `AUTO`는 `RANDOM`으로 풀려 v4가 된다.
3절이 기본을 v7로 정했으므로, 생략하면 사유 없이 v4를 쓰게 되어 `IDS-3-01`과 조용히 어긋난다.
v4 예외 대상 테이블은 `style = RANDOM`을 명시한다.

```java
// 서비스는 식별자를 모른다
@Transactional
public UUID register(String email) {
    Account account = Account.register(email);
    accountRepository.save(account);
    return account.getPublicId();    // save 이후에 값이 있다
}
```

### 5.1 내장 구현이 4절을 지키는가

`IDS-4-03`은 도입한 구현의 난수원을 확인하라고 요구한다. 내장 `UuidVersion7Strategy`를 대조한 결과다.

| 4절 항목 | 내장 구현 | |
|---|---|---|
| `IDS-4-01` `SecureRandom`인가 | `new SecureRandom()` | 지킴 |
| `IDS-4-02` `getInstanceStrong()` 금지 | 쓰지 않음 | 지킴 |
| `IDS-4-04` 증가값이 CSPRNG에서 나오는가 | `lastSequence + nextLong(0xFFFF_FFFFL)` | 지킴 |
| `IDS-4-05` 밀리초가 바뀌면 다시 뽑는가 | `new State(now, randomSequence())` | 지킴 |

rand_a 12비트는 서브밀리초 시각으로 채워진다(Method 3). 난수가 74비트에서 62비트로 줄고 생성 시각이 약 250µs 단위까지 드러난다.
**이 손실은 받아들인다.** 밀리초는 어차피 타임스탬프에 있고, 62비트는 인가 검증(`IDS-3-03`)이 앞단에 있는 식별자로 충분하다.

### 5.2 무엇을 포기했는가

**사전 채번을 포기했다.** `save()` 전에는 `public_id`가 `null`이다.

내장 생성기의 `generate()`는 `currentValue`를 보지 않고 항상 새 값을 반환한다.
**값을 미리 넣어도 INSERT 때 덮어쓰인다.** 예외 경로가 없다는 뜻이다.

| | 이전 (생성자 인자) | 지금 (ORM) |
|---|---|---|
| 값이 정해지는 시점 | 엔티티 생성 | INSERT 직전 |
| 저장 전 로그, 이벤트에 사용 | 가능 | **불가** |
| 서비스마다 생성기 호출 | 필요 | **불필요** |
| 테스트에서 값 고정 | 가능 | **불가.** `save()` 후에 읽는다 |
| 특정 값 지정 | 가능 | **불가.** 백필은 SQL로 한다 |

저장 전에 식별자가 필요한 흐름이 실제로 생기면 이 결정을 되돌린다.
`@ValueGenerationType`으로 `currentValue`를 보존하는 생성기를 두면 되고, 비트 배치는 그때도 내장 전략에 위임한다.

점검 항목
* `IDS-5-01` `public_id`가 `@UuidGenerator`로 채워지는가
  엔티티나 서비스가 직접 대입하면 3절의 버전 규칙을 우회하는 경로가 생긴다.
* `IDS-5-02` `style`을 명시했는가
  기본값 `AUTO`는 `RANDOM`으로 풀려 v4가 된다. 생략하면 사유 없이 v4를 쓰게 된다.
* `IDS-5-03` `GenerationType.UUID`를 쓰지 않았는가
  JPA 표준은 버전을 규정하지 않고 Hibernate는 v4로 구현한다. 버전을 고를 수 없다.
* `IDS-5-04` `public_id` 컬럼에 `updatable = false`가 지정되어 있는가
  생성이 INSERT로 한정되는 것은 `@UuidGenerator`가 `EventTypeSets.INSERT_ONLY`를 반환해 보장한다.
  이 항목은 우리 쪽 잠금이다. 필드에 대입하는 코드가 생겨도 UPDATE 문에 실리지 않는다.

## 6. 스키마

```sql
CREATE TABLE account (
    account_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id  BINARY(16)      NOT NULL,
    PRIMARY KEY (account_id),
    UNIQUE KEY uk_account_public_id (public_id)
) ENGINE=InnoDB;
```

| 규칙 | 근거 |
|---|---|
| 컬럼명은 전 프로젝트 공통 `public_id` | 이름이 갈리면 `@MappedSuperclass` 공통 매핑을 쓸 수 없다 |
| `BINARY(16)`, `CHAR(36)` 금지 | utf8mb4에서 최대 144바이트가 되어 인덱스가 9배가 된다 |
| `NOT NULL` | NULL을 허용하면 모든 조회 경로에 방어 코드가 생긴다 |
| FK는 내부 `BIGINT` 참조 | 16바이트 조인은 하이브리드를 채택한 이유를 무효화한다 |
| `UUID_TO_BIN` 스왑 플래그 미사용 | 스왑은 v1 교정 기능이다. v4와 v7에는 의미가 없다 |
| `DEFAULT (UUID())` 금지 | 사전 채번이 막히고 v1이 유입된다 |

점검 항목
* `IDS-6-01` `public_id`가 `BINARY(16)`이고 `NOT NULL`인가
* `IDS-6-02` `DEFAULT (UUID())`를 걸지 않았는가
* `IDS-6-03` FK와 조인 조건이 내부 식별자를 쓰는가

## 7. 계층별 사용 규칙

| 위치 | 사용할 식별자 | 근거 |
|---|---|---|
| API 경로, 요청/응답 본문 | 외부 | 내부 구조와 규모를 노출하지 않는다 |
| 서비스 간 이벤트 페이로드 | 외부 | 경계를 넘어도 의미가 유지된다 |
| 외부 시스템 연동 | 외부 | 유출 시 열거로 번지지 않는다 |
| 애플리케이션 로그 | 내부 | 짧고, 유출돼도 API 접근에 쓸 수 없다 |
| DB 조인, FK | 내부 | 8바이트 비교 |
| 배치, 대량 조회 | 내부 | UNIQUE에서 PK로 가는 2단계가 행 수만큼 반복된다 |

저장 전 단계에서는 **두 식별자가 다 없다.** 내부 ID는 INSERT 후에, 외부 식별자는 INSERT 직전에 정해진다 (5절).
그 구간의 로그는 요청 식별자나 도메인 값으로 남긴다.

점검 항목
* `IDS-7-01` 응답 DTO나 API 경로에 내부 `Long id`가 없는가
  한 번 노출되면 클라이언트가 의존하기 시작해 되돌릴 수 없다.
* `IDS-7-02` 대량 조회 경로가 외부 식별자 목록으로 돌지 않는가
* `IDS-7-03` 응답 DTO의 필드명이 `publicId`가 아니라 `id`인가
  클라이언트에게는 그것이 유일한 식별자다. 다른 ID의 존재를 알릴 이유가 없다.
* `IDS-7-04` 서비스 진입 메서드가 외부 식별자를 둘 이상 나열해서 받지 않는가
  `UUID`가 나란히 있으면 순서를 바꿔 넣어도 컴파일이 통과하고, 조회가 비거나 조용히 틀린 값이 저장된다.
  커맨드 객체로 묶으면 순서를 틀릴 자리가 사라진다. 타입 래퍼를 두지 않는 대신 이 규칙이 그 자리를 맡는다.

## 8. 타입과 예외

점검 항목
* `IDS-8-01` 외부 입력 UUID 파싱 실패가 400으로 매핑되는가
  `UUID.fromString`의 `IllegalArgumentException`을 그대로 두면 500이 나간다.

## 9. 정렬

점검 항목
* `IDS-9-01` UUID를 정렬 키나 커서 페이지네이션 키로 쓰지 않는가
  v7도 같은 밀리초 내 순서는 보장되지 않아 행이 건너뛰어진다. 정렬은 시각 컬럼과 PK 조합으로 한다.

## 10. 비즈니스 식별자

사람이 전화로 부르거나 종이에 적어야 하는 번호가 필요하면 외부 식별자와 별개로 만든다.

```
주문번호: 20260805-0001A
송장번호: 물류사 발급
```

점검 항목
* `IDS-10-01` 비즈니스 식별자를 API 식별자로 쓰지 않는가
  규칙이 있어 추측 가능하다. 조회 파라미터로는 쓸 수 있으나 그때도 소유권 검증이 선행되어야 한다.

## 11. 신규 프로젝트 도입 체크리스트

* [ ] 2절 판단 순서로 구성을 결정하고 근거를 남겼다
* [ ] 3절에 따라 기본 버전을 확정하고 v4 예외 대상을 식별했다
* [ ] 클라이언트 생성 식별자 허용 여부를 결정했다
* [ ] `common-core`에 `@MappedSuperclass`를 배치했다
* [ ] 2절 기준으로 적용 대상 테이블을 확정하고 ERD에 반영했다
* [ ] 비즈니스 식별자가 필요한 리소스와 채번 규칙을 정했다
* [ ] 테스트가 `save()` 이후에 식별자를 읽도록 작성했다

## 12. 참고

* RFC 9562, Universally Unique IDentifiers: https://www.rfc-editor.org/rfc/rfc9562.html
* RFC 4122 Section 6, Security Considerations: https://datatracker.ietf.org/doc/html/rfc4122
* MySQL 8.4, Miscellaneous Functions: https://dev.mysql.com/doc/refman/8.4/en/miscellaneous-functions.html
* MySQL 8.4, Online DDL Operations: https://dev.mysql.com/doc/refman/8.4/en/innodb-online-ddl-operations.html
