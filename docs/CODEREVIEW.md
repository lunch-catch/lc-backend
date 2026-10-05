# 코드 리뷰 가이드라인

이 문서는 Pull Request 자동 코드 리뷰의 기준을 정의하는 진입점이다.
공통 규칙과 각 영역 문서로의 링크, 그리고 변경 경로별 적용 매핑을 담는다.

기준은 두 디렉터리에 있다. `docs/code-convention` 이 **어떻게 쓰는가**(코드 관용과 패턴)를,
`docs/software-quality` 가 **얼마나 잘 하는가**(품질 속성)를 소유한다.

대상 기술 스택은 Java 21, Spring Boot 4.x, MySQL 8.4, Valkey 9를 기준으로 한다.

## 문서 구성

각 영역은 점검 항목을 정의한 가이드 문서와, 그 항목이 왜 필요한지 설명하는 근거 문서로 짝을 이룬다.
가이드는 리뷰 시 점검 기준으로 쓰고, 근거는 판단이 애매할 때 맥락을 이해하기 위해 참고한다.

| 영역 | 점검 가이드 | 근거 문서 |
|------|-------------|-----------|
| 자바 작성 원칙 (Effective Java 기반) | [effective-java-guideline.md](./code-convention/effective-java-guideline.md) | [effective-java-rationale.md](./code-convention/effective-java-rationale.md) |
| 단위 테스트 (Unit Testing 기반) | [unit-testing-guideline.md](./code-convention/unit-testing-guideline.md) | [unit-testing-rationale.md](./code-convention/unit-testing-rationale.md) |
| API 설계 (Google AIP 기반) | [api-design-guideline.md](./code-convention/api-design-guideline.md) | [api-design-rationale.md](./code-convention/api-design-rationale.md) |
| 엔티티 생성 패턴 | [entity-creation-guideline.md](./code-convention/entity-creation-guideline.md) | [entity-creation-rationale.md](./code-convention/entity-creation-rationale.md) |
| JPA 연관 매핑 (애그리거트 경계) | [jpa-association-guideline.md](./code-convention/jpa-association-guideline.md) | [jpa-association-rationale.md](./code-convention/jpa-association-rationale.md) |
| 도메인 경계와 의존 방향 | [domain-boundary-guideline.md](./code-convention/domain-boundary-guideline.md) | [domain-boundary-rationale.md](./code-convention/domain-boundary-rationale.md) |
| 빌드 게이트 (커버리지, 정적 분석) | [build-gate-guideline.md](./code-convention/build-gate-guideline.md) | [build-gate-rationale.md](./code-convention/build-gate-rationale.md) |
| 응답과 예외 흐름 | [response-exception-flow.md](./code-convention/response-exception-flow.md) | - |

### 품질 속성 (`docs/software-quality`)

진입점은 [quality-attributes.md](./software-quality/quality-attributes.md) 다. ISO/IEC 25010:2023 을
기준 모델로 삼고, 영역마다 `*-guideline.md` 와 `*-rationale.md` 가 짝을 이룬다.

| 영역 | 접두어 | 점검 가이드 |
|------|--------|-------------|
| 기능 적합성 | `FUN-` | [qa-functional-suitability-guideline.md](./software-quality/qa-functional-suitability-guideline.md) |
| 성능 효율성 | `PERF-` | [qa-performance-efficiency-guideline.md](./software-quality/qa-performance-efficiency-guideline.md) |
| 신뢰성 | `REL-` | [qa-reliability-guideline.md](./software-quality/qa-reliability-guideline.md) |
| 보안 | `SEC-` | [qa-security-guideline.md](./software-quality/qa-security-guideline.md) |
| 유지보수성 | `MNT-` | [qa-maintainability-guideline.md](./software-quality/qa-maintainability-guideline.md) |
| 유연성과 확장성 | `FLX-` | [qa-flexibility-guideline.md](./software-quality/qa-flexibility-guideline.md) |
| 호환성 | `CMP-` | [qa-compatibility-guideline.md](./software-quality/qa-compatibility-guideline.md) |
| 데이터 정합성 | `DI-` | [qa-data-integrity-guideline.md](./software-quality/qa-data-integrity-guideline.md) |
| 관측 가능성 | `OBS-` | [qa-observability-guideline.md](./software-quality/qa-observability-guideline.md) |
| 인시던트 대응 | `INC-` | [qa-incident-response-guideline.md](./software-quality/qa-incident-response-guideline.md) |
| 속성 간 트레이드오프 | `TRD-` | [qa-tradeoffs-guideline.md](./software-quality/qa-tradeoffs-guideline.md) |

**자동 리뷰가 보는 것은 219개 중 93개다.** `[코드]` 79개 전부, 그리고 파일 하나로 판정되는
`[설계]` 6개(마이그레이션 SQL)와 `[인프라]` 8개(`application.yml`, `logback-spring.xml`)다.
나머지 126개는 PR 하나의 변경분으로 판정할 수 없다. `[설계]` 는 설계 리뷰와 ADR 에서,
`[인프라]` 는 인프라 변경에서, `[프로세스]` 는 정기 점검에서 사람이 본다.

수치에 붙은 등급은 근거의 성격이다. `A` 는 산술로 도출한 값, `B` 는 출처가 있는 값,
`C` 는 근거 없이 정한 예시값이다. **`C` 로 지적하지 않는다.** 측정한 뒤 팀이 확정할 값이다.

**경계 규약의 설명은 설계 문서가 소유한다.** 계층, 도메인, `contract` 패키지, 의존 규칙 6개,
그것을 지키는 아키텍처 테스트 12개가 모두 그 문서 1장과 2장에 있다.
`domain-boundary-guideline.md` 는 그것을 **번호 붙인 점검 항목으로만** 옮긴 것이고,
`domain-boundary-rationale.md` 는 왜 그것이 리뷰 항목인지와 테스트가 못 보는 것을 다룬다.
둘 다 규칙의 내용을 다시 설명하지 않는다. 설명을 두 곳에 두면 어긋나는 순간 어느 쪽이
기준인지 알 수 없게 된다.

## 봇 동작 규칙

자동 리뷰 봇은 **CodeRabbit** 이다. 설정은 저장소 루트의 `.coderabbit.yaml` 에 있고, 아래 규칙과
파일명 힌트를 그 파일의 `path_instructions` 로 전달한다. **판정 기준은 두 디렉터리의 가이드가
갖고 그 파일은 배선일 뿐이다.** 규칙을 바꿀 때는 가이드를 먼저 고치고 그 파일을 맞춘다.

배선이 둘로 나뉘고 역할이 다르다. 섞으면 조용히 작동하지 않는다.

| 설정 | 역할 |
|---|---|
| `knowledge_base.code_guidelines.filePatterns` | 가이드 문서를 **판정 기준으로 읽게** 한다 |
| `reviews.path_instructions` | 경로마다 **무엇을 볼지** 지시한다 |

**`filePatterns` 는 반드시 `files` 와 `applyTo` 를 쓰는 객체 형태로 적는다.** 문자열 항목은
그 가이드를 자기가 담긴 디렉터리와 하위 디렉터리에만 적용한다. `docs/code-convention/*.md` 를
문자열로 적으면 그 디렉터리의 파일을 리뷰할 때만 참조되고 `src/main/java` 를 리뷰할 때는
가이드가 **아예 연결되지 않는다.** 실제로 그 상태로 한동안 돌았고, 지적의 출처가 전부
`Path instructions` 로 찍히고 `Code guidelines` 가 0건인 것으로 드러났다.

반대 실수도 있다. 가이드 파일 이름을 `path_instructions` 에 적으면 그 문서를 **리뷰 대상
코드로** 본다. 기준으로 쓰이지 않는다.

봇은 다음 규칙을 따른다.

1. 지적의 기준은 두 디렉터리의 점검 가이드 문서(`*-guideline.md`)에 적힌 점검 항목으로 한정한다.
   품질 속성 가이드는 기본으로 `[코드]` 항목만 쓴다. `[설계]` 와 `[인프라]` 는 `.coderabbit.yaml`
   의 경로 지시가 번호를 직접 열거한 경우에만 쓴다. 파일 하나로 판정되는 항목만 열려 있고,
   지금은 마이그레이션 SQL 에 `[설계]` 6개, `application.yml` 과 `logback-spring.xml` 에
   `[인프라]` 8개다. `[프로세스]` 는 어느 경로에서도 쓰지 않는다.
2. 근거 문서(`*-rationale.md`)는 코멘트 설명을 보강할 때만 참고하고, 근거 문서를 바탕으로 새로운 지적을 만들지 않는다.
3. 가이드에 없는 항목은 지적하지 않으며, 새 점검 기준이 필요하면 가이드 문서를 먼저 갱신한다.
4. 가이드에 포함된 외부 링크와 출처(AIP 번호, 책 항목 번호, URL 등)는 사람 리뷰어와 작성자를 위한 참고일 뿐이다. 봇은 이 링크를 가져오지 않으며, 가이드 본문에 적힌 내용만으로 판단한다. 외부 페이지를 읽지 않으면 판단이 어려운 항목은 임의로 가져오지 말고, 확인이 필요하다는 점을 코멘트로 남겨 사람에게 넘긴다.
5. 한 사안에 대해서는 한 번만 지적한다. 여러 가이드가 같은 문제를 다룰 수 있으므로, 봇은 아래 소유권 우선순위에 따라 그 사안을 소유한 가이드 하나만 기준으로 코멘트를 남긴다.
6. **지적마다 근거를 정해진 모양으로 붙인다.** 번호만 쓰거나 문장만 쓰지 않고 둘을 함께 쓴다.

   **문서별로 묶는다.** 문서 이름을 링크로 걸고 그 문서의 항목만 목록으로 나열한다.
   전체 경로는 본문에 적지 않고 링크 주소에만 둔다.

   ```markdown
   근거 [entity-creation-guideline.md](링크)

   - `EC-1-01` 엔티티에 `@Setter`, `@Data`가 붙어 있지 않은가
   - `EC-1-02` 클래스 레벨 `@Builder`, `@AllArgsConstructor`가 없는가
   ```

   문서가 둘 이상이면 묶음을 반복한다. **목록 앞뒤에 빈 줄을 둔다.** 빈 줄이 없으면
   마크다운이 줄을 합쳐 한 덩어리로 보인다. 처음에 항목마다 경로를 한 줄씩 붙이는 모양을
   썼는데, 같은 경로가 네 번 반복되고 줄바꿈이 합쳐져 읽히지 않았다.

   항목 내용은 가이드의 문장을 **그대로** 옮긴다. 요약하거나 번역하거나 고쳐 쓰지 않는다.
   번호와 경로를 추측하지 않으며, 가이드에서 찾지 못하면 그 지적을 만들지 않는다.
   번호가 없는 규약(`response-exception-flow.md`, 설계 문서, API 명세)은 번호 자리에 절이나
   제목을 적는다.

   **`.coderabbit.yaml` 의 경로 지시가 인용 문자열을 완성해 둔다.** 자주 쓰는 항목 113개를
   경로별로 전개해 두었고, 봇은 그것을 복사한다. 지시에 없는 항목으로 지적할 때는 가이드에서
   번호와 경로를 찾아 같은 형식으로 쓴다.

   **가이드의 항목 문장을 고치면 그 설정도 함께 고친다.** 두 곳이 어긋나면 봇이 인용하는 문장이
   가이드와 달라진다. 항목을 더하거나 번호를 바꿀 때도 같다. 유지비를 아는 선택이고, 번호와
   경로 없는 인용으로는 읽는 사람이 문서를 뒤져야 한다는 비용과 맞바꾼 것이다.

7. 모든 리뷰 코멘트는 한국어로 작성한다. 지적 강도 접두어(`[BLOCKER]` 등), 코드 식별자, 애너테이션, 파일 경로, AIP 번호 같은 고유 표기는 원문 그대로 두되, 설명과 제안 문장은 한국어로 쓴다.

### 중복 지적 방지 소유권 우선순위

같은 코드 한 줄에 여러 가이드가 걸릴 때, **더 구체적이고 좁은 범위를 다루는 가이드가 그 사안을 소유한다.**
봇은 소유 가이드에서만 지적하고, 더 일반적인 가이드의 동일 항목은 발화하지 않는다.

엔티티를 다루는 가이드가 둘이다. 축을 나눠 소유를 정한다. entity-creation 은 인스턴스를 어떻게
만드는지를 보고, jpa-association 은 필드가 무엇을 가리키는지를 본다.

| 사안 | 소유 가이드 | 지적 보류 |
|------|-------------|-----------|
| 엔티티 인스턴스 생성 (정적 팩터리, 검증 위치, 생성용 Lombok) | entity-creation-guideline.md | effective-java-guideline.md |
| 연관 매핑 범위와 방식 (객체 연관 대 ID, fetch, cascade, 애그리거트 경계) | jpa-association-guideline.md | - |
| 엔티티 속성값의 저장 방식 (enum 대 코드 테이블) | entity-creation-guideline.md | - |
| 패키지 배치, 도메인 간 참조, 순환 의존 | domain-boundary-guideline.md (`DPB-`) | - |
| 오류 코드와 예외 클래스 배치, 응답 봉투 | response-exception-flow.md | effective-java-guideline.md |
| 자바 관용 (불변, 예외 흐름, 컬렉션 반환, 상속보다 조합) | effective-java-guideline.md | - |
| 테스트 설계와 품질 (동작 검증, 테스트 더블, 구조, 격리) | unit-testing-guideline.md | - |
| API 표면 설계 (리소스, 표준 메서드, 필드명, 페이지네이션, 오류 구조) | api-design-guideline.md | - |
| 커버리지 게이트와 정적 분석 설정 | build-gate-guideline.md | - |
| 트랜잭션 경계와 길이, 외부 호출 위치 | qa-data-integrity-guideline.md (`DI-4-*`) | - |
| 잠금 전략과 획득 순서, 갱신 손실 | qa-data-integrity-guideline.md (`DI-2-*`) | - |
| N+1 과 쿼리 반복 | qa-performance-efficiency-guideline.md (`PERF-2-*`) | jpa-association-guideline.md |
| 인가와 소유권 검증, 입력 검증, 인젝션 | qa-security-guideline.md (`SEC-1-*`, `SEC-2-*`, `SEC-3-*`) | - |
| 타임아웃, 재시도, 멱등성 | qa-reliability-guideline.md (`REL-2-*`) | - |
| 로그 내용과 상관관계 ID, 민감정보 마스킹 | qa-observability-guideline.md (`OBS-3-*`, `OBS-7-*`) | - |
| 오류 응답이 내부를 노출하는지 | qa-compatibility-guideline.md (`CMP-4-04`) | response-exception-flow.md |

품질 속성 가이드와 코드 관용 가이드가 같은 줄에 걸릴 때의 경계는 이렇다.
**관용 가이드는 "이 프로젝트에서 그것을 어떤 모양으로 쓰는가" 를, 품질 속성 가이드는
"그것이 충분한가" 를 본다.**

예를 들어 지연 로딩 한 줄에서 `jpa-association-guideline.md` 는 `fetch = LAZY` 를 명시했는지를
보고, `qa-performance-efficiency-guideline.md` 는 그 지연 로딩이 루프 안에서 N+1 을 만드는지를
본다. 둘은 같은 사안의 다른 관점이라 중복이 아니다.

오류 응답은 반대로 겹친다. `response-exception-flow.md` 가 "`ErrorCode` 에서만 문구가 나온다" 로
이미 더 좁게 막으므로 `CMP-4-04` 는 그 규약을 지킨 코드에 발화하지 않는다.

### 품질 속성은 어디가 보는가

아래는 시스템 품질 속성이라 코드 관용과는 다른 축이다. **전에는 소유 문서가 없어 사람 리뷰어가
전부 봤는데, `docs/software-quality` 를 들여오면서 소유 가이드를 갖게 되었다.**

| 사안 | 소유 가이드 | 프로젝트의 구체값은 어디 |
|------|-------------|--------------------------|
| 트랜잭션 경계, 외부 호출 위치, 트랜잭션 길이 | `DI-4-*` | 설계 문서 2.4절의 이벤트 처리 기준 |
| 잠금 전략, 획득 순서, 갱신 손실 | `DI-2-*` | 아직 저장소에 없다. 정하면 이 칸을 채운다 |
| N+1, 인덱스, 쿼리 성능 | `PERF-2-*` | 아직 저장소에 없다. 응답 시간 목표를 정하면 이 칸을 채운다 |
| 인가와 소유권 검증 | `SEC-1-*` | [api-spec/README.md](./api-spec/README.md) 의 인증 절, 설계 문서의 의존 규칙 5 |
| 타임아웃, 재시도, 서킷 브레이커 | `REL-2-*` | `application.yml` 의 resilience4j 설정과 그 주석 |

**가이드는 유형을 주고 수치는 주지 않는다.** 임계치가 대부분 등급 `C`(근거 없는 예시값)라서
"타임아웃이 설정되어 있는가" 는 판정하지만 "3초가 맞는가" 는 판정하지 않는다. 그 값은 오른쪽
열의 프로젝트 문서가 정한다. 둘이 다르게 말하면 프로젝트 문서가 이긴다.

그리고 남은 126개는 소유 가이드가 있어도 **자동 리뷰가 지적하지 않는다.**
`[설계]` 는 설계 리뷰와 ADR, `[인프라]` 는 인프라 변경, `[프로세스]` 는 정기 점검에서 사람이 본다.
**그 세 절차는 아직 만들어지지 않았다.** 지금은 아무도 보지 않는 상태라는 뜻이다.

소유 문서가 아직 없는 것은 하나 남았다.

| 사안 | 지금 어디를 근거로 보는가 |
|------|--------------------------|
| 엔티티 뼈대와 시각 컬럼, 식별자 전략 | `global.entity` 의 `BaseTimeEntity`, `docs/api-spec/README.md` 의 식별자 절 |

경계 기준은 **품질 속성은 "얼마나 잘 하는가", `code-convention` 은 "어떻게 쓰는가"(코드 관용과 패턴)**다.

해석 원칙은 다음과 같다.

- **같은 사안이라도 관점이 다르면 중복이 아니다.** 예를 들어 엔티티 클래스 하나에서 jpa-association은 연관 필드가 무엇을 가리키는지를, entity-creation은 생성 경로를 보므로 둘 다 발화할 수 있다. 표는 "같은 문제를 같은 관점으로 두 번 지적하는 것"만 막는다.
- 우선순위가 불분명하면 더 좁은 범위를 다루는 가이드를 소유로 본다.
- **엔티티 뼈대와 식별자 전략은 소유 문서가 없다.** 위 "다루지 않는 사안" 표를 따른다.

## 가이드 적용 대상 판단

effective-java-guideline.md와 설계 문서의 경계 규칙은 변경 위치와 무관하게 모든 프로덕션 자바 PR에 항상 적용한다.
나머지는 변경 내용을 기준으로 적용 여부를 판단한다.

**테스트 코드(`src/test/**`)는 이 둘의 대상이 아니다.**
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
| jpa-association-guideline.md | `@ManyToOne`, `@OneToMany`, `@OneToOne`, `@ManyToMany`, `@JoinColumn`, `cascade`, `fetch`, `orphanRemoval`, `@Embedded`, `@ElementCollection`, 엔티티의 `~Id` 필드 |
| domain-boundary-guideline.md | 패키지 이동, import 문 변경, 접근 제어자 변경, `contract` 패키지의 인터페이스와 record, `~Event` 클래스, ArchUnit 테스트 |
| response-exception-flow.md | `ErrorCode`, `BusinessException`, `ResponseEnvelope`, `@RestControllerAdvice`, 새 오류 코드 enum |
| unit-testing-guideline.md | `@Test`, JUnit, Mockito, AssertJ, `@DataJpaTest`, `@SpringBootTest`, 테스트 클래스(`*Test`) |
| api-design-guideline.md | `@RestController`, `@RequestMapping`, `@GetMapping`/`@PostMapping`/`@PatchMapping`/`@DeleteMapping`, 요청과 응답 DTO, OpenAPI 명세 |
| build-gate-guideline.md | `build.gradle` 의 jacoco, sonar, check 관련 블록, `.github/workflows/**` |
| qa-security-guideline.md | 쿼리 문자열 조립, 요청 본문의 식별자를 그대로 쓰는 조회, 파일 업로드, 정렬 컬럼 바인딩, 비밀값 리터럴 |
| qa-data-integrity-guideline.md | `@Transactional`, 잠금(`@Lock`, `@Version`, `SELECT FOR UPDATE`), 트랜잭션 안의 외부 호출, 수량 차감 |
| qa-performance-efficiency-guideline.md | 반복문 안의 조회, 컬렉션 순회 중 연관 접근, 페이지네이션, 대량 조회 |
| qa-reliability-guideline.md | `WebClient`, `RestClient`, 타임아웃과 재시도 설정, `@Retryable`, 서킷 브레이커, 멱등 키 |
| qa-observability-guideline.md | 로깅 호출, MDC, 예외 처리에서 남기는 정보, 메트릭 등록 |
| qa-compatibility-guideline.md | 응답 필드 제거나 이름 변경, enum 값 삭제, 마이그레이션의 컬럼 제거, 오류 응답 본문 |
| qa-maintainability-guideline.md | 긴 메서드와 깊은 분기, 중복 블록, 죽은 코드, 설정 하드코딩 |

### 파일명 힌트 (보조)

최종 적용 여부는 위의 내용 시그널로 확정한다.

| 파일명 패턴 | 적용 문서 |
|-------------|-----------|
| `src/main/**` 의 모든 변경 | effective-java-guideline.md, domain-boundary-guideline.md |
| `**/*Entity.java`, `**/entity/**` | entity-creation-guideline.md, jpa-association-guideline.md |
| `**/*Controller.java`, `**/dto/**`, OpenAPI 명세 | api-design-guideline.md |
| `**/contract/**`, `**/*Event.java` | domain-boundary-guideline.md (`DPB-1-*`, `DPB-4-*`) |
| `**/*Repository.java` | jpa-association-guideline.md |
| `**/*Test.java`, `src/test/**` | unit-testing-guideline.md |
| `**/ArchitectureTest.java` | domain-boundary-guideline.md, 설계 문서 1.5절과 2.5절 |
| `src/main/**` 의 모든 변경 | 품질 속성 가이드의 `[코드]` 항목 (내용 시그널로 어느 영역인지 좁힌다) |
| `**/auth/**`, `**/*Controller.java` | qa-security-guideline.md |
| `**/service/**` | qa-data-integrity-guideline.md, qa-reliability-guideline.md |
| `**/repository/**` | qa-performance-efficiency-guideline.md |
| `src/main/resources/db/migration/**/*.sql` | qa-compatibility-guideline.md |

### 도메인 경계 점검의 자동화

도메인형 구조에서는 경로보다 도메인 간 경계가 더 중요하다.
ArchUnit 아키텍처 테스트로 "한 도메인이 다른 도메인의 `entity` 와 `repository` 를 참조하지 않는다",
"`contract` 에는 인터페이스와 record, enum 만 둔다", "순환 의존이 없다" 같은 규칙을 강제한다.

**이는 보조 수단이 아니라 필수다.** 계층을 나눈 대가로 `Service`, `Repository`, `Entity`가 public이 되므로,
접근 제어자만으로는 경계가 지켜지지 않는다. 그래서 팀은 이 테스트를 선택이 아니라 필수로 둔다.
규칙 12개의 목록과 그 코드는 설계 문서 1.5절과 2.5절에 있다.

## 지적 강도 분류

리뷰 코멘트는 다음 접두어 중 하나를 붙여 우선순위를 명확히 한다.

**접두어는 우선순위 표시이고 병합을 막지 않는다.** 병합을 막는 것은 `G-BUILD` 하나뿐이다
(build-gate-guideline.md). LLM 판정은 재현율이 측정되지 않았으므로 차단하지 않는다. 오탐으로
병합이 막히기 시작하면 우회 문화가 생긴다. `[BLOCKER]` 는 "사람이 반드시 처리하고 넘어가야
한다" 는 뜻이고, 그 처리는 작성자와 리뷰어가 합의로 한다.

| 접두어 | 의미 | 처리 기대 |
|--------|------|-----------|
| `[BLOCKER]` | 그대로 두면 안 되는 것 | 고치거나, 고치지 않는 이유를 PR 에 남긴다 |
| `[MAJOR]` | 강하게 권장하는 수정 | 작성자와 리뷰어가 협의해 정한다 |
| `[MINOR]` | 제안 수준 | 작성자 판단 |
| `[NIT]` | 단순 의견, 취향 | 무시해도 된다 |

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
  (참고: 런치캐치_도메인_구조와_의존성_설계.md 2.4절)

[MINOR] AuditLog.java:12
  시각 컬럼을 직접 선언하고 있습니다.
  BaseTimeEntity 를 상속하면 created_at 과 updated_at 을 함께 얻습니다.
  (참고: global.entity 의 BaseTimeEntity)
```
