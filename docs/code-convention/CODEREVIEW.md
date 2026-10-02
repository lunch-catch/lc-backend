# 코드 리뷰 가이드라인

이 디렉터리는 Pull Request 자동 코드 리뷰의 기준을 정의한다.
이 문서(CODEREVIEW.md)는 진입점 역할을 하며, 공통 규칙과 각 영역 문서로의 링크, 그리고 변경 경로별 적용 매핑을 담는다.

대상 기술 스택은 Java 21, Spring Boot 4.x, MySQL 8.4, Valkey 9를 기준으로 한다.

## 문서 구성

각 영역은 점검 항목을 정의한 가이드 문서와, 그 항목이 왜 필요한지 설명하는 근거 문서로 짝을 이룬다.
가이드는 리뷰 시 점검 기준으로 쓰고, 근거는 판단이 애매할 때 맥락을 이해하기 위해 참고한다.

| 영역 | 점검 가이드 | 근거 문서 |
|------|-------------|-----------|
| 자바 작성 원칙 (Effective Java 기반) | [effective-java-guideline.md](./effective-java-guideline.md) | [effective-java-rationale.md](./effective-java-rationale.md) |
| 단위 테스트 (Unit Testing 기반) | [unit-testing-guideline.md](./unit-testing-guideline.md) | [unit-testing-rationale.md](./unit-testing-rationale.md) |
| API 설계 (Google AIP 기반) | [api-design-guideline.md](./api-design-guideline.md) | [api-design-rationale.md](./api-design-rationale.md) |
| 엔티티 생성 패턴 | [entity-creation-guideline.md](./entity-creation-guideline.md) | [entity-creation-rationale.md](./entity-creation-rationale.md) |
| JPA 연관 매핑 (애그리거트 경계) | [런치캐치_JPA_연관_규칙.md](../architecture/런치캐치_JPA_연관_규칙.md) | - |
| 빌드 게이트 (커버리지, 정적 분석) | [build-gate-guideline.md](./build-gate-guideline.md) | [build-gate-rationale.md](./build-gate-rationale.md) |
| 응답과 예외 흐름 | [response-exception-flow.md](./response-exception-flow.md) | - |
| 도메인 패키지 경계와 의존 방향 | [런치캐치_백엔드_설계.md](../architecture/런치캐치_백엔드_설계.md) | - |

**경계 규약은 이 디렉터리가 아니라 설계 문서가 소유한다.** 계층, 도메인, `contract` 패키지, 의존 규칙 6개,
그것을 지키는 아키텍처 테스트 12개가 모두 그 문서 1장과 2장에 있다. 같은 내용을 여기에 다시 적으면
둘이 어긋나는 순간 어느 쪽이 기준인지 알 수 없게 된다.

## 봇 동작 규칙

자동 리뷰 봇은 다음 규칙을 따른다.

1. 지적의 기준은 점검 가이드 문서(`*-guideline.md`)의 점검 항목으로 한정한다.
2. 근거 문서(`*-rationale.md`)는 코멘트 설명을 보강할 때만 참고하고, 근거 문서를 바탕으로 새로운 지적을 만들지 않는다.
3. 가이드에 없는 항목은 지적하지 않으며, 새 점검 기준이 필요하면 가이드 문서를 먼저 갱신한다.
4. 가이드에 포함된 외부 링크와 출처(AIP 번호, 책 항목 번호, URL 등)는 사람 리뷰어와 작성자를 위한 참고일 뿐이다. 봇은 이 링크를 가져오지 않으며, 가이드 본문에 적힌 내용만으로 판단한다. 외부 페이지를 읽지 않으면 판단이 어려운 항목은 임의로 가져오지 말고, 확인이 필요하다는 점을 코멘트로 남겨 사람에게 넘긴다.
5. 한 사안에 대해서는 한 번만 지적한다. 여러 가이드가 같은 문제를 다룰 수 있으므로, 봇은 아래 소유권 우선순위에 따라 그 사안을 소유한 가이드 하나만 기준으로 코멘트를 남긴다.
6. 모든 리뷰 코멘트는 한국어로 작성한다. 지적 강도 접두어(`[BLOCKER]` 등), 코드 식별자, 애너테이션, 파일 경로, AIP 번호 같은 고유 표기는 원문 그대로 두되, 설명과 제안 문장은 한국어로 쓴다.

### 중복 지적 방지 소유권 우선순위

같은 코드 한 줄에 여러 가이드가 걸릴 때, **더 구체적이고 좁은 범위를 다루는 가이드가 그 사안을 소유한다.**
봇은 소유 가이드에서만 지적하고, 더 일반적인 가이드의 동일 항목은 발화하지 않는다.

엔티티를 다루는 가이드가 둘이다. 축을 나눠 소유를 정한다. entity-creation 은 인스턴스를 어떻게
만드는지를 보고, JPA 연관 규칙은 필드가 무엇을 가리키는지를 본다.

| 사안 | 소유 가이드 | 지적 보류 |
|------|-------------|-----------|
| 엔티티 인스턴스 생성 (정적 팩터리, 검증 위치, 생성용 Lombok) | entity-creation-guideline.md | effective-java-guideline.md |
| 연관 매핑 범위와 방식 (객체 연관 대 ID, fetch, cascade, 애그리거트 경계) | 런치캐치_JPA_연관_규칙.md | - |
| 엔티티 속성값의 저장 방식 (enum 대 코드 테이블) | entity-creation-guideline.md | - |
| 패키지 배치, 도메인 간 참조, 접근 제어자, 순환 의존 | 설계 문서 1장과 2장 | - |
| 오류 코드와 예외 클래스 배치, 응답 봉투 | response-exception-flow.md | effective-java-guideline.md |
| 자바 관용 (불변, 예외 흐름, 컬렉션 반환, 상속보다 조합) | effective-java-guideline.md | - |
| 테스트 설계와 품질 (동작 검증, 테스트 더블, 구조, 격리) | unit-testing-guideline.md | - |
| API 표면 설계 (리소스, 표준 메서드, 필드명, 페이지네이션, 오류 구조) | api-design-guideline.md | - |
| 커버리지 게이트와 정적 분석 설정 | build-gate-guideline.md | - |

### 이 디렉터리가 다루지 않는 사안

아래는 시스템 품질 속성이라 코드 관용과는 다른 축이다. **지금은 소유 문서가 없으므로 사람 리뷰어가 본다.**
자동 리뷰 봇은 이 사안으로 지적하지 않는다. 기준이 필요해지면 그때 가이드를 새로 만든다.

| 사안 | 지금 어디를 근거로 보는가 |
|------|--------------------------|
| 트랜잭션 경계, 외부 호출 위치, 트랜잭션 길이 | 설계 문서 2.4절의 이벤트 처리 기준 |
| 잠금 전략, 획득 순서, 갱신 손실 | 요구사항 명세서 `비즈니스 규칙` 시트, 비기능 17행 |
| N+1, 인덱스, 쿼리 성능 | 비기능 12~16행의 응답 시간 목표 |
| 인가와 소유권 검증 | 기능 명세서 94행, 의존 규칙 5 |
| 타임아웃, 재시도, 서킷 브레이커 | `application.yml` 의 resilience4j 설정과 그 주석 |
| 엔티티 뼈대와 시각 컬럼, 식별자 전략 | `global.entity` 의 베이스 엔티티 둘, `docs/api/README.md` 의 식별자 절 |

경계 기준은 **품질 속성은 "얼마나 잘 하는가", 이 디렉터리는 "어떻게 쓰는가"(코드 관용과 패턴)**다.

해석 원칙은 다음과 같다.

- **같은 사안이라도 관점이 다르면 중복이 아니다.** 예를 들어 엔티티 클래스 하나에서 JPA 연관 규칙은 연관 필드가 무엇을 가리키는지를, entity-creation은 생성 경로를 보므로 둘 다 발화할 수 있다. 표는 "같은 문제를 같은 관점으로 두 번 지적하는 것"만 막는다.
- 우선순위가 불분명하면 더 좁은 범위를 다루는 가이드를 소유로 본다.
- **엔티티 뼈대와 식별자 전략은 소유 문서가 없다.** 위 "다루지 않는 사안" 표를 따른다.

## 가이드 적용 대상 판단

effective-java-guideline.md와 설계 문서의 경계 규칙은 변경 위치와 무관하게 모든 프로덕션 자바 PR에 항상 적용한다.
나머지는 변경 내용을 기준으로 적용 여부를 판단한다.

**테스트 코드(`src/test/**`, `src/integrationTest/**`)는 이 둘의 대상이 아니다.**
두 문서는 프로덕션 코드를 겨냥하므로 목 주입, 픽스처 빌더, 서술형 메서드명이 전부 지적으로 나온다.
테스트는 unit-testing-guideline.md가 소유한다.
예외는 `ArchitectureTest`다. 테스트 파일이지만 판정 대상이 경계 규칙 자체이므로 설계 문서를 적용한다.

이 프로젝트는 도메인형 구조(package-by-feature)를 사용한다.
도메인 패키지 안에 Controller, Service, Repository, Entity가 함께 모이므로 계층 디렉터리로 영역을 구분할 수 없다.
따라서 적용 판단은 내용 시그널을 주된 기준으로 삼고, 파일명 힌트는 보조로만 쓴다.

1. 내용 시그널: diff에 아래 시그널이 보이면 해당 가이드를 적용한다. (주된 기준)
2. 파일명 힌트: 시그널 판단을 빠르게 좁히기 위한 보조 단서로 쓴다.
3. 애매하면 적용: 관련성이 불확실하면 적용하는 쪽을 택한다. 불필요한 코멘트가 누락된 점검보다 비용이 낮기 때문이다.

### 내용 시그널 (주된 기준)

| 가이드 | 적용 시그널 |
|--------|-------------|
| entity-creation-guideline.md | `@Entity` 클래스의 생성자와 정적 팩터리, `@Builder`, `@Setter`, `@Data`, `@NoArgsConstructor`, `@Enumerated` |
| 런치캐치_JPA_연관_규칙.md | `@ManyToOne`, `@OneToMany`, `@OneToOne`, `@ManyToMany`, `@JoinColumn`, `cascade`, `fetch`, `orphanRemoval`, `@Embedded`, `@ElementCollection`, 엔티티의 `~Id` 필드 |
| 설계 문서 1장과 2장 | 패키지 이동, import 문 변경, 접근 제어자 변경, `contract` 패키지의 인터페이스와 record, `~Event` 클래스, ArchUnit 테스트 |
| response-exception-flow.md | `ErrorCode`, `BusinessException`, `ResponseEnvelope`, `@RestControllerAdvice`, 새 오류 코드 enum |
| unit-testing-guideline.md | `@Test`, JUnit, Mockito, AssertJ, `@DataJpaTest`, `@SpringBootTest`, 테스트 클래스(`*Test`) |
| api-design-guideline.md | `@RestController`, `@RequestMapping`, `@GetMapping`/`@PostMapping`/`@PatchMapping`/`@DeleteMapping`, 요청과 응답 DTO, OpenAPI 명세 |
| build-gate-guideline.md | `build.gradle` 의 jacoco, sonar, check 관련 블록, `.github/workflows/**` |

### 파일명 힌트 (보조)

최종 적용 여부는 위의 내용 시그널로 확정한다.

| 파일명 패턴 | 적용 문서 |
|-------------|-----------|
| `src/main/**` 의 모든 변경 | effective-java-guideline.md, 설계 문서 1장과 2장 |
| `**/*Entity.java`, `**/entity/**` | entity-creation-guideline.md, 런치캐치_JPA_연관_규칙.md |
| `**/*Controller.java`, `**/dto/**`, OpenAPI 명세 | api-design-guideline.md |
| `**/contract/**`, `**/*Event.java` | 설계 문서 1.3절과 2.4절 |
| `**/*Repository.java` | 런치캐치_JPA_연관_규칙.md |
| `**/*Test.java`, `src/test/**`, `src/integrationTest/**` | unit-testing-guideline.md |
| `**/ArchitectureTest.java` | 설계 문서 1.5절과 2.5절 |

### 도메인 경계 점검의 자동화

도메인형 구조에서는 경로보다 도메인 간 경계가 더 중요하다.
ArchUnit 아키텍처 테스트로 "한 도메인이 다른 도메인의 `entity` 와 `repository` 를 참조하지 않는다",
"`contract` 에는 인터페이스와 record, enum 만 둔다", "순환 의존이 없다" 같은 규칙을 강제한다.

**이는 보조 수단이 아니라 필수다.** 계층을 나눈 대가로 `Service`, `Repository`, `Entity`가 public이 되므로,
접근 제어자만으로는 경계가 지켜지지 않는다. 비기능 31행이 이 테스트를 필수로 못 박았다.
규칙 12개의 목록과 그 코드는 설계 문서 1.5절과 2.5절에 있다.

## 지적 강도 분류

리뷰 코멘트는 다음 접두어 중 하나를 붙여 우선순위를 명확히 한다.

| 접두어 | 의미 | 머지 차단 여부 |
|--------|------|----------------|
| `[BLOCKER]` | 반드시 수정해야 머지 가능 | 차단 |
| `[MAJOR]` | 강하게 권장하는 수정 | 협의 후 결정 |
| `[MINOR]` | 제안 수준 | 비차단 |
| `[NIT]` | 단순 의견, 취향 | 비차단 |

## 리뷰 코멘트 작성 원칙

1. 문제를 지적할 때는 이유와 개선 방향을 함께 제시한다.
2. 단정적 명령보다 근거를 들어 설명한다.
3. 좋은 부분도 함께 언급하여 균형을 맞춘다.
4. 지적이 과도하게 많으면 BLOCKER와 MAJOR부터 정리해서 전달한다.

## 자동 리뷰 출력 형식 예시

```
[BLOCKER] Coupon.java:42
  public 생성자가 열려 있어 검증 생성자를 거치지 않는 생성 경로가 생깁니다.
  정적 팩터리 하나로 노출하고 검증을 private 생성자에 모아 주세요.
  (참고: entity-creation-guideline.md R2)

[BLOCKER] IssueService.java:18
  다른 도메인의 내부 패키지(adserving.repository)를 import 하고 있습니다.
  adserving.contract 의 WishlistQueryService 를 통해 읽어 주세요.
  (참고: 런치캐치_백엔드_설계.md 2.4절)

[MINOR] AuditLog.java:12
  수정되지 않는 이력 테이블인데 BaseMutableTimeEntity 를 상속하고 있습니다.
  BaseImmutableTimeEntity 가 적합해 보입니다.
  (참고: global.entity 의 베이스 엔티티 둘)
```
