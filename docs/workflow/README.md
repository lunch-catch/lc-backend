# 개발 흐름

| 항목 | 내용 |
|---|---|
| 목적 | 변경이 저장소에서 운영까지 가는 경로와 그 길에 있는 게이트를 한곳에 둔다 |
| 범위 | 브랜치 전략, 보호 설정, 게이트 판정, merge queue, 배포 경로 |
| 관련 문서 | `../../AGENTS.md`(작업 절차 5단계), `로컬_실행.md`(내 기계에서 띄우기), `../CODEREVIEW.md`(리뷰 기준 진입점) |

**작업 절차 자체는 여기 적지 않는다.** 이슈를 열고 브랜치를 만들고 PR 을 올리는 5단계는 `../../AGENTS.md` 가 갖는다. 이 문서는 그 절차가 지나가는 길의 모양을 적는다.

## 1. 브랜치

| 브랜치 | 역할 | 받는 출처 |
|---|---|---|
| `develop` | 기본 브랜치. 기능 브랜치가 모인다 | 기능 브랜치 |
| `main` | 운영 배포 기준 | `develop`, `release/*`, `hotfix/*` 만 |
| `<종류>/<이슈번호>-<설명>` | 기능 브랜치 | `develop` 에서 분기 |

종류는 `feat`, `fix`, `chore`, `docs`, `test`, `refactor` 를 쓴다.

`main` 의 출처 제한은 브랜치 보호에 그런 항목이 없어서 `G-BUILD` 의 첫 스텝이 검사한다. 체크아웃보다 앞에 있어 틀리면 바로 떨어진다.

### 기본 브랜치가 `develop` 인 이유

`Closes #<번호>` 키워드는 **base 가 기본 브랜치인 PR 에만** 걸린다. 기능 PR 이 전부 `develop` 으로 가므로 기본 브랜치가 `main` 이면 이슈가 자동으로 닫히지 않는다. `issues` 와 `pull_request_review` 이벤트로 도는 워크플로도 기본 브랜치의 파일만 실행한다.

## 2. 보호 설정

| 항목 | `develop` | `main` |
|---|---|---|
| 필수 상태 검사 | `G-BUILD` | `G-BUILD` |
| 승인 | 1 | 1 |
| 최신 상태 요구(`strict`) | 끔 | 끔 |
| merge queue | 켬 | 끔 |
| 강제 푸시와 삭제 | 금지 | 금지 |

브랜치 보호는 저장소 설정이라 어느 파일에도 없다. merge queue 만 ruleset(`develop merge queue`)으로 걸려 있어 `Settings > Rules > Rulesets` 에 있다.

### merge queue

`develop` 의 머지 버튼은 `Merge when ready` 다. 누르면 바로 병합되지 않고 큐에 들어가서, GitHub 이 최신 `develop` 과 합친 임시 브랜치에서 `G-BUILD` 를 돌린 뒤 통과하면 넣는다. **`Update branch` 를 누를 일이 없다.**

| 설정 | 값 |
|---|---|
| 머지 방식 | 머지 커밋 |
| 묶기 전략 | `ALLGREEN`. 묶인 항목 전부가 초록이어야 넣는다 |
| 동시 빌드 | 5 |

`strict` 를 쓰지 않는 이유는 비용이 선형이 아니어서다. PR 다섯이 승인을 받아도 하나를 머지하면 나머지 넷이 `behind` 가 되어 사람 다섯이 차례로 재검사를 기다린다. 큐는 같은 검사를 사람 손 없이 한다. 근거는 `../code-convention/build-gate-guideline.md` 의 `BLD-3-06` 에 있다.

## 3. 게이트

병합을 막는 것은 `G-BUILD` 하나다. `./gradlew check` 를 돌린다.

| 판정 | 기준 | 주체 |
|---|---|---|
| 커버리지 | `com.launchcatch.*.service.*` 의 **클래스별 메서드 100%** | Gradle `jacocoTestCoverageVerification` |
| 커버리지 데이터 | 검증 대상이 있는데 실행 데이터가 없는 상태를 막는다 | Gradle `coverageDataCheck` |
| 정적 분석 | 신규 `Blocker` 이슈 0건 | SonarCloud 이슈 검색 API |
| 아키텍처 | 계층과 도메인 경계 | ArchUnit 검사 14개 |
| `main` 출처 | `develop`, `release/*`, `hotfix/*` | `G-BUILD` 첫 스텝 |

클래스별 판정이라 평균으로 가려지지 않는다. 한 클래스만 미달해도 막힌다.

LLM 리뷰는 CodeRabbit 이 맡고 **차단하지 않는다.** 재현율이 측정되지 않은 판정으로 병합을 막으면 오탐이 쌓여 우회 문화가 생긴다. 설정은 루트의 `.coderabbit.yaml` 이고 판정 기준은 `../CODEREVIEW.md` 가 진입점이다.

판정 기준 전문은 `../code-convention/build-gate-guideline.md` 에 있다.

## 4. 배포

| 대상 | 트리거 | 워크플로 |
|---|---|---|
| 개발 서버 | `develop` 에 push | `.github/workflows/deploy-dev.yml` |
| 운영 | `main` 에 push | `.github/workflows/deploy.yml` |

마이그레이션은 기동할 때 돈다. `db/migration/{도메인}` 마다 Flyway 를 따로 돌리고 이력도 도메인별로 둔다. 규약은 `../../src/main/resources/db/migration/README.md` 에 있다.

운영 배포는 이미 올린 이미지가 있으면 push 를 건너뛴다. ECR 저장소가 IMMUTABLE 이라 같은 SHA 를 다시 밀 수 없어서, 사전 점검에서 멈춘 배포를 재실행하면 그 단계에서 막혔다.

배치 서버는 같은 jar 를 `batch` 프로필로 띄운 서버다. 구성과 운영 규칙은 `../ops/런치캐치_배치_운영.md` 에 있다.

## 5. 릴리스

`develop -> main` PR 을 올린다. 이슈는 만들지 않는다. 합치는 일 자체는 작업이 아니다.

`main` 에는 큐가 없어 승인 뒤 바로 병합되고, 병합이 곧 운영 배포다. 그래서 PR 본문에 **운영에 닿는 것** 을 적는다.

- 새로 들어가는 마이그레이션과 그것이 기존 데이터에 걸릴 조건
- 외부 설정이 필요한 변경 (예: 카카오 동의 항목, 새 시크릿)
- 되돌리기 어려운 스키마 변경
