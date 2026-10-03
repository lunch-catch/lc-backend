# JPA 연관 매핑 점검 항목의 근거

이 문서는 [jpa-association-guideline.md](./jpa-association-guideline.md)의 점검 항목이 왜 필요한지를
규칙의 서술과 코드 예시로 설명한다. 점검 항목 번호(`AGG-`)는 각 장의 규칙에서 나온 것이다.

**규칙의 서술은 이 문서가 소유한다.** 가이드는 그것을 판정할 수 있는 질문으로 옮긴 것이므로,
규칙을 고칠 때는 이 문서를 먼저 고치고 가이드의 점검 항목을 맞춘다.

| 항목 | 내용 |
|---|---|
| 기준 | 런치캐치 도메인 구조와 의존성 설계 2.1 규칙 1 |
| 원칙 | 각 도메인은 자기 애그리거트(함께 바뀌어야 하는 데이터 묶음)만 직접 읽고 쓴다. 다른 도메인의 데이터는 그 도메인이 공개한 기능으로만 다룬다 |
| 스택 | Spring Boot, Spring Data JPA, MySQL 8.4 |

---

## 1. 도메인 간 연관은 ID로만 갖는다

다른 도메인 엔티티에는 `@ManyToOne` 같은 연관을 걸지 않고 ID 값만 필드로 둔다. ArchUnit `엔티티는_다른_도메인_엔티티를_참조하지_않는다`가 검사한다.

```java
package com.launchcatch.coupon.entity;

@Entity
public class Coupon {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long campaignId;   // Campaign 객체가 아니라 ID (campaign 도메인)
    private Long memberId;     // Member 객체가 아니라 ID (member 도메인)
}
```

다른 도메인 데이터가 필요하면 그 도메인 `contract`의 조회 인터페이스로 record를 받는다. 여러 건이면 ID 목록으로 한 번에 받는 메서드를 두어 N+1을 피한다.

```java
package com.launchcatch.campaign.contract;

public interface CampaignQueryService {
    List<CampaignSummary> findSummaries(Collection<Long> campaignIds);
}
```

## 2. 같은 도메인 안에서도 다른 애그리거트는 ID로 갖는다

애그리거트가 다르면 저장 시점과 트랜잭션도 다르다. 객체 연관으로 묶으면 한쪽을 저장할 때 다른 쪽까지 끌려오기 쉽다. 같은 도메인이라도 애그리거트 경계를 넘을 때는 ID를 쓴다.

## 3. 객체 연관은 애그리거트 안에서만 쓴다

- 루트가 자식의 생명주기를 가질 때만 `@OneToMany(cascade = ALL, orphanRemoval = true)`를 쓴다.
- 자식은 루트를 통해서만 추가하고 삭제한다.
- 저장소(Repository)는 애그리거트 루트에만 둔다.
- 따로 식별할 필요가 없는 값은 엔티티 대신 `@Embedded`나 `@ElementCollection`으로 둔다.

```java
@Entity
public class Campaign {

    @OneToMany(mappedBy = "campaign", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CampaignTimeSlot> timeSlots = new ArrayList<>();

    @Embedded
    private CouponCondition couponCondition;   // 값 객체

    public void addTimeSlot(LocalTime start, LocalTime end) {
        timeSlots.add(new CampaignTimeSlot(this, start, end));
    }
}

@Entity
public class CampaignTimeSlot {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id")
    private Campaign campaign;   // 연관관계의 주인 (FK가 있는 쪽)
}
```

`CampaignTimeSlot`, `CouponCondition`과 1장의 `findSummaries`, `CampaignSummary`는 설명을 위한 예시 이름이다.

## 4. 연관 매핑 세부 규칙

| 규칙 | 이유 |
|---|---|
| `@ManyToOne`, `@OneToOne`은 모두 `fetch = LAZY` | 기본값이 EAGER라 원치 않는 조인과 N+1이 생긴다 |
| 양방향은 꼭 필요할 때만 두고, 연관관계의 주인은 FK가 있는 쪽 | 양쪽을 맞추는 코드가 늘고, `toString`과 JSON 직렬화에서 순환이 생긴다 |
| `@ManyToMany`는 쓰지 않는다 | 중간 테이블에 컬럼(생성 시각 등)을 더할 수 없다. 연결 엔티티를 직접 둔다 |
| `cascade`는 애그리거트 안에서만 | 경계를 넘는 cascade는 남의 애그리거트를 대신 저장하거나 지운다 |
| `contract`의 record와 이벤트에 엔티티를 싣지 않는다 | 받는 쪽이 남의 `entity` 패키지를 import하게 된다(백엔드 설계 2.4 페이로드 규칙) |

## 5. 테스트로 지키는 것

1번은 이미 ArchUnit `엔티티는_다른_도메인_엔티티를_참조하지_않는다`가 지킨다. 4번 표의 일부도 필드 규칙으로 더할 수 있다.

```java
@ArchTest
static final ArchRule ManyToMany는_쓰지_않는다 =
        noFields().should().beAnnotatedWith(ManyToMany.class);
```

2번과 3번(애그리거트 경계)은 패키지로 구분되지 않아 테스트로 잡기 어렵다. 코드 리뷰에서 본다.
