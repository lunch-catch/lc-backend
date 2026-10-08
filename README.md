# lc-backend

런치캐치 백엔드. 점주가 점심 할인 캠페인을 등록하고, 사용자가 주변 가게를 스와이프 피드로 받아 찜하고 쿠폰을 발급받는 서비스다. 노출은 점주가 건 하루 예산과 반경, 성별과 연령대 조건에 따라 배정되고 포인트로 과금된다.

## 기술 스택

| | 버전 |
|---|---|
| Java | 21 (`eclipse-temurin:21.0.9`) |
| Spring Boot | 4.0.5 |
| MySQL | 8.4 |
| Valkey | 9.0 |
| Gradle | 8.14.3 (wrapper) |

JPA 와 QueryDSL, Flyway, JJWT 0.12.7, resilience4j, springdoc-openapi 3.0.3 을 쓴다. 테스트는 JUnit 5, Testcontainers, ArchUnit 1.4.1, JaCoCo 다.

## 시작하기

```bash
cp application-local.yml.example application-local.yml   # 값을 채운다
./gradlew bootRun
curl http://localhost:8081/actuator/health
```

**설정 파일을 만들지 않으면 뜨지 않는다.** 채울 값과 막혔을 때 볼 곳은 [로컬 실행](./docs/local/README.md)에 있다.

| | 주소 |
|---|---|
| API | `http://localhost:8080` |
| 액추에이터 | `http://localhost:8081/actuator/health` |
| API 문서 | `http://localhost:8080/swagger-ui.html` |

## 구조

계층은 셋이고 아래 계층은 위를 의존하지 않는다.

| 계층 | 무엇 | 규칙 |
|---|---|---|
| 업무 도메인 10개 | 기능과 데이터를 소유한다 | 서로의 `contract` 만 참조한다 |
| `auth`, `ops` | 모든 도메인이 쓰는 하위 모듈 | 업무 도메인을 의존하지 않는다 |
| `global` | 예외, 응답 포맷, 거리 계산 | 아무것도 의존하지 않는다 |

도메인 안은 `contract/`(공개면: 인터페이스, record, enum), `controller/`, `listener/` 와 `job/`, `service/`, `repository/`, `entity/` 로 나뉜다. **경계를 넘는 참조는 FK 가 아니라 ID 값이다.** 자세한 규칙과 의존 그래프는 [도메인 구조와 의존성 설계](./docs/architecture/런치캐치_도메인_구조와_의존성_설계.md)에 있다.

### 도메인과 담당자

| 도메인 | 다루는 것 | 담당 |
|---|---|---|
| `admin` | 관리자 계정, 점주와 사용자 관리, 플랫폼 설정값, 감사 로그 | @gyudongjeong |
| `owner` | 점주 계정, 튜토리얼 | @gyudongjeong |
| `store` | 점주 입점 흐름, 가게 목록과 검색과 상세 | @gyudongjeong |
| `notification` | 사용자 알림, 점주 발송 현황 | @gyudongjeong |
| `member` | 온보딩, 위치 설정, 회원정보, 탈퇴, 동의 | @muzimzz |
| `billing` | 포인트 결제, 잔액과 내역, 환불 | @muzimzz |
| `analytics` | 대시보드, 매출, 무효 노출, 리포트, 전환 퍼널 | @muzimzz |
| `campaign` | 캠페인 등록 4단계와 상태 전이, 포스터와 템플릿 | @yongmaru789 |
| `ops` | 설정값, 스케줄러, 감사 로그 | @yongmaru789 |
| `adserving` | 스와이프 피드, 찜과 패스, 노출 수집, 배정 진단 | @devjohnpark |
| `coupon` | 선착순 발급, 쿠폰함, 동적 QR, 사용 처리 | @MinhyeokChoi99 |
| `auth`, `global` | 공용 인증과 기술 공통 | @devjohnpark |

[`.github/CODEOWNERS`](./.github/CODEOWNERS)가 경로를 보고 리뷰어를 자동으로 요청한다.

## 개발 흐름

1. 이슈를 먼저 연다.
2. 그 이슈에서 브랜치를 만든다. `gh issue develop <번호> --base develop --name <종류>/<번호>-<설명> --checkout`
3. PR 본문에 `Closes #<번호>` 를 적는다.
4. 리뷰어의 승인을 받는다.
5. 작성자가 `Merge when ready` 로 큐에 넣는다.

규칙 전문은 [AGENTS.md](./AGENTS.md)에 있다. `CLAUDE.md` 는 같은 내용이다.

| 브랜치 | 역할 | 보호 |
|---|---|---|
| `develop` | 기본 브랜치. 기능 브랜치가 모인다 | `G-BUILD` 필수, 승인 1, merge queue |
| `main` | 운영 배포 기준 | `G-BUILD` 필수, 승인 1. `develop`, `release/*`, `hotfix/*` 에서만 받는다 |

### 게이트

병합을 막는 것은 `G-BUILD` 하나다. `./gradlew check` 를 돌린다.

| 판정 | 기준 |
|---|---|
| 커버리지 | `com.launchcatch.*.service.*` 의 **클래스별 메서드 100%** |
| 정적 분석 | 신규 `Blocker` 이슈 0건 (SonarCloud) |
| 아키텍처 | ArchUnit 검사 14개가 계층과 도메인 경계를 본다 |

LLM 리뷰는 CodeRabbit 이 맡고 차단하지 않는다. 기준 문서는 [코드 리뷰 기준](./docs/CODEREVIEW.md)이 진입점이다.

### 배포

| 대상 | 트리거 |
|---|---|
| 개발 서버 | `develop` 에 push |
| 운영 | `main` 에 push |

마이그레이션은 기동할 때 돈다. `db/migration/{도메인}` 마다 Flyway 를 따로 돌리고 이력도 도메인별로 둔다.

## 문서

| 문서 | 내용 |
|---|---|
| [로컬 실행](./docs/local/README.md) | 설정 파일 만들기, 실행, 막혔을 때 보는 곳 |
| [작업 규칙](./AGENTS.md) | 이슈와 PR, 커밋, 문서와 주석 |
| [코드 리뷰 기준](./docs/CODEREVIEW.md) | 변경 경로별로 적용되는 가이드 |
| [도메인 구조와 의존성 설계](./docs/architecture/런치캐치_도메인_구조와_의존성_설계.md) | 도메인 경계와 의존 방향 |
| [배치 운영](./docs/architecture/런치캐치_배치_운영.md) | 배치 서버 구성과 운영 규칙 |
| [알림 운영](./docs/architecture/런치캐치_알림_운영.md) | Slack 알림 구성과 설정 절차 |
| [API 명세](./docs/api-spec/README.md) | 공통 규약과 도메인별 명세 |
| [마이그레이션 규약](./src/main/resources/db/migration/README.md) | 스키마를 소유하는 SQL 의 배치와 규칙 |

`docs/code-convention/`(어떻게 쓰는가)과 `docs/software-quality/`(얼마나 잘 하는가)가 리뷰 판정 기준을 소유한다. 영역마다 점검 항목(`*-guideline.md`)과 근거(`*-rationale.md`)가 짝을 이룬다. 어느 변경에 무엇이 적용되는지는 `docs/CODEREVIEW.md` 의 매핑표를 본다.
