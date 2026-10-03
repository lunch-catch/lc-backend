# 빌드 게이트 (guideline)

팀은 `build.gradle` 과 `settings.gradle` 을 고치는 PR에 이 문서를 적용한다.
설계 근거는 [build-gate-rationale.md](./build-gate-rationale.md) 에 있다.

**이 문서의 항목은 LLM이 아니라 도구가 판정한다.** 결정론적이므로 병합을 차단해도 근거가 있다.

| 게이트 | 판정 주체 | 조건 | 동작 |
|--------|-----------|------|------|
| 커버리지 | Gradle `jacocoTestCoverageVerification` | `com.launchcatch.*.service.*` 메서드 100% | **병합 차단** |
| 정적 분석 | SonarQube Quality Gate | **신규 Blocker 이슈 0건** | **병합 차단** |
| 브랜치 전략 | `G-BUILD` 첫 스텝 | `main` 의 PR 출처가 `develop`, `release/*`, `hotfix/*` | **병합 차단** |

## 1. 커버리지

점검 항목
* `BLD-1-01` JaCoCo 대상이 `com.launchcatch.*.service.*`로 좁혀져 있는가
  `includes`로 좁히므로 팀에게 exclude 목록이 필요 없다. config, dto, entity, Q클래스가 자동으로 빠진다. 패턴이 클래스 이름이 아니라 패키지 전체를 가리키므로 `service` 안에 무엇을 두어도 대상에서 빠지지 않는다.
* `BLD-1-02` 판정 단위가 클래스별(`element = 'CLASS'`), 카운터가 메서드(`counter = 'METHOD'`)인가
* `BLD-1-03` 기준이 `minimum = 1.00`인가
* `BLD-1-04` 통합 테스트로 커버리지를 채우지 않는가
  계층을 가로지르는 테스트는 메서드를 지나가기만 해도 커버리지가 차서, 서비스 로직을 단위 테스트 없이 통과시킬 수 있다.
  소스셋이 하나라 실행 기록이 `test.exec` 으로 모이므로 Gradle 이 둘을 가르지 못한다. 이름(`~IntegrationTest`)이 유일한 단서이고 판정은 리뷰어가 한다.
* `BLD-1-05` `check`가 `jacocoTestCoverageVerification`과 `coverageDataCheck`에 의존하는가
* `BLD-1-06` `jacocoTestReport`가 `sonar` 태스크보다 먼저 도는가
  순서가 바뀌면 SonarQube에 커버리지가 0으로 표시된다.
* `BLD-1-07` 검증 대상 클래스가 있는데 실행 데이터가 없는 상태를 막는가
  `jacocoTestCoverageVerification`은 `.exec`가 하나도 없으면 Gradle이 태스크를 통째로 건너뛴다.
  건너뛴 것은 초록으로 보이므로, 팀원이 테스트를 하나도 안 쓰면 무사통과하고 하나라도 쓰면 100%를 요구하는 거꾸로 걸린 게이트가 된다.
  게이트가 대상 클래스의 존재와 실행 데이터의 존재를 따로 확인해 이 구간을 막는다.

## 2. 정적 분석

점검 항목
* `BLD-2-01` SonarQube Quality Gate가 커버리지를 판정하지 않는가
  판정 주체가 둘이면 기본 게이트값(신규 코드 80% 등)이 Gradle 기준과 충돌한다.
  무료 플랜은 커스텀 게이트를 만들 수 없어 팀이 조건을 뺄 수 없으므로, 팀은 `sonar.qualitygate.wait` 을 켜지 않는 것으로 대신한다.
* `BLD-2-02` 정적 분석 차단 조건이 신규 `Blocker` 이슈 0건인가
  `Blocker`는 프로덕션에서 애플리케이션을 망가뜨릴 높은 확률의 버그를 뜻한다. 병합을 막을 근거가 되는 것은 이 등급뿐이다.
  게이트는 그 아래 등급을 차단하지 않고 경고로만 표시한다.
  차단은 워크플로가 이슈 검색 API 로 신규 `Blocker` 수를 직접 세어 수행한다.
* `BLD-2-03` 브랜치 보호의 필수 상태 검사에 `G-BUILD`가 등록되어 있는가
  커버리지와 정적 분석은 한 잡(`G-BUILD`) 안에서 함께 돌므로 등록되는 검사 이름은 하나다. 이것이 두 기준을 강제하는 유일한 수단이다.
  판정은 사람이 한다. 필수 상태 검사 목록은 저장소 설정이라 어느 파일에도 없고, 자동 리뷰는 diff 만 본다. `BLD-3-01` 이 이 항목을 `develop` 까지 넓힌 것이다.
  팀은 `G-PR`(LLM 판정)을 일부러 등록하지 않는다. 팀이 재현율이 측정되지 않은 판정으로 병합을 막으면 오탐이 쌓여 우회 문화가 생긴다.
* `BLD-2-04` `SonarCloud Code Analysis` 검사를 필수 상태 검사로 등록하지 않았는가
  SonarCloud GitHub 앱이 올리는 이 검사는 내장 `Sonar way` 게이트로 판정하므로 신규 코드 커버리지 80% 조건을 포함한다.
  `BLD-2-01` 이 커버리지 판정 주체를 Gradle 하나로 둔 결정과 정면으로 어긋난다. 등록하면 그 결정이 무효가 된다.
  `BLD-2-03` 과 같은 이유로 판정은 사람이 한다.
* `BLD-2-05` 판정하지 않는 스텝이 게이트를 막지 않는가
  차단 조건은 머리의 표 셋이다. 그 밖의 스텝은 결과를 보기 편하게 만드는 것이라 실패해도 병합을 막아서는 안 된다.
  **`continue-on-error: true` 를 둔다. 이것이 필수다.** `if` 는 실행 시점을 좁히는 것이고 실패 처리와 무관하다.
  조건이 참이어서 스텝이 돌고 그 안에서 실패하면, `continue-on-error` 가 없는 한 잡도 함께 실패한다.
  둘을 "또는" 으로 읽으면 `if` 만 좁혀 두고 규칙을 지켰다고 생각하게 되는데, 그 스텝이 도는 날 같은 일이 다시 난다.
  `if` 는 실행 횟수를 줄여 외부 서비스에 기댈 일을 줄이는 데 쓴다. 실패를 막는 수단이 아니다.
  2026-10-03 에 테스트 리포트 업로드가 GitHub 아티팩트 서비스 타임아웃으로 실패해, 커버리지와 정적 분석과 병합 출처가 모두 통과한 PR 의 병합이 막혔다.
  외부 서비스의 가용성이 병합 조건에 섞이면 게이트가 무엇을 판정하는지가 흐려지고, 사람이 재실행 버튼을 누르는 습관이 생긴다. 그 습관은 진짜 실패에도 같이 쓰인다.

### 무료 플랜에서 차단하는 방법

팀은 커스텀 Quality Gate 를 Team 플랜부터 쓸 수 있다. 무료 플랜은 내장 `Sonar way` 뿐인데
거기에는 신규 코드 커버리지 80% 조건이 들어 있어 팀이 그대로 켜면 커버리지 판정이 둘이 된다.

그래서 워크플로가 `sonar.qualitygate.wait` 을 쓰지 않고 이슈 검색 API 로 신규 `Blocker` 만 센다.
이 API 는 무료 플랜에서도 동작하며, 세는 대상이 정확히 `BLD-2-02` 가 요구하는 것이다.

```bash
curl -u "$SONAR_TOKEN:" \
  "https://sonarcloud.io/api/issues/search?componentKeys=<키>&severities=BLOCKER&resolved=false&pullRequest=<번호>"
```

팀이 Team 플랜으로 올리면 커스텀 게이트에서 커버리지 조건을 빼고 `qualitygate.wait` 을 켜는 편이 낫다.
그때는 팀이 이 단계를 지운다.

```gradle
jacocoTestCoverageVerification {
    executionData.setFrom fileTree(layout.buildDirectory.dir('jacoco')).include('test.exec')
    violationRules {
        rule {
            element = 'CLASS'
            includes = ['com.launchcatch.*.service.*']
            limit {
                counter = 'METHOD'
                value = 'COVEREDRATIO'
                minimum = 1.00
            }
        }
    }
}

check.dependsOn jacocoTestCoverageVerification, coverageDataCheck
```

#### `SonarCloud Code Analysis` 검사가 빨간 것은 정상이다

워크플로가 게이트를 기다리지 않아도 **SonarCloud GitHub 앱은 자기 검사를 따로 올린다.**
그 판정은 내장 `Sonar way` 로 하므로 신규 코드 커버리지 80% 를 못 채우면 빨강이 된다.

```
new_coverage                    ERROR   실제 0.0%   기준 80%
new_reliability_rating          OK
new_security_rating             OK
new_maintainability_rating      OK
new_duplicated_lines_density    OK
new_security_hotspots_reviewed  OK
```

커버리지 게이트가 `com.launchcatch.*.service.*` 만 보도록 좁혀져 있으므로, 문서나 설정,
베이스 엔티티처럼 **그 범위 밖을 고치는 PR 은 거의 항상 이 검사가 빨강이다.** 설계대로다.

**고치려 들지 않는다.** 손댈 자리가 셋 있는데 둘은 틀린 선택이다.

| 하고 싶어지는 것 | 왜 안 되는가 |
|---|---|
| 필수 상태 검사에 등록해 빨강을 처리한다 | 커버리지 판정이 둘이 된다. `BLD-2-04` 가 막는다 |
| 커버리지 게이트 범위를 전체로 넓혀 80% 를 채운다 | `BLD-1-01` 이 좁힌 이유가 사라진다. 엔티티와 설정에 의미 없는 테스트가 붙는다 |
| Team 플랜의 커스텀 게이트에서 커버리지 조건을 뺀다 | 맞는 방향이다. 유료라 지금은 선택지가 아니다 |

**그래서 이 검사는 읽을 거리이고 판정이 아니다.** 판정은 `G-BUILD` 하나다.
빨간 검사가 상주하는 비용은 "빨강을 무시하는 습관" 인데, 필수 검사가 하나뿐이라 그 하나가
빨강인 것과 구분된다. 새로 온 사람이 헷갈리지 않도록 이 절을 남긴다.

### 2.1 100% 기준과 "커버리지를 목표로 삼지 말라"는 원칙의 관계

backend `unit-testing-guideline.md`의 `UT-6-03`은 "커버리지 숫자 자체를 목표로 삼지 않는가"를 묻는다.
표면상 100% 강제와 충돌해 보이지만 **재는 것이 다르다.**

| | 재는 것 | 보장하는 것 |
|---|---|---|
| METHOD 100% | **범위** | 모든 메서드에 테스트가 한 번은 지나갔다 |
| `UT-6-03` | **깊이** | 그 테스트가 실제로 검증하는가 |

METHOD 카운터는 메서드가 호출되었는지만 본다. JaCoCo 는 100줄 중 1줄만 지나가도 커버된 것으로 계산한다.

```java
public void placeOrder(OrderCommand cmd) {
    validate(cmd);              // 여기서 예외 발생
    stockService.deduct(cmd);   // 실행 안 됨
    orderRepository.save(...);  // 실행 안 됨
}
```

**실패 경로만 테스트해도 이 메서드는 100%로 계산된다.**
그래서 깊이 검증은 게이트가 아니라 코드 리뷰와 테스트 작성 규칙이 맡는다. 두 항목은 역할이 겹치지 않는다.

특히 **조건부 UPDATE의 `affected rows == 0` 분기는 정합성 최종 방어선(INF-1-05)이므로 팀원이 반드시 실패 경로 테스트를 함께 작성한다.**

### 2.2 로컬에서도 같은 게이트를 돌린다

팀원은 push 전에 `./gradlew check`로 확인한다. 팀원이 CI에서 처음 알면 이미 PR을 연 뒤다.

**기준이 100%라 여유가 없다.** 팀원이 새 메서드를 하나 추가하고 테스트를 빠뜨리면 그 순간부터 모든 병합이 막힌다.
의도된 엄격함이지만, 팀원이 로컬에서 먼저 돌리지 않으면 CI 실패로 알게 되어 왕복이 생긴다.


## 3. 브랜치 전략

기능 브랜치는 `develop` 에서 분기해 `develop` 으로 돌아온다. `main` 은 `develop`, `release/*`, `hotfix/*` 에서만 받는다.

점검 항목
* `BLD-3-01` `main` 과 `develop` 둘 다 `G-BUILD` 를 필수 상태 검사로 두었는가
  `develop` 이 무방비면 기능 브랜치가 검사 없이 들어오고, `main` 으로 가는 PR 하나가 누적된 변경을 한꺼번에 받는다.
  `BLD-2-03` 이 `main` 에 요구하는 것과 같은 설정을 `develop` 에도 둔다.
  필수 검사 등록은 둘 다 같지만 "최신 상태 요구"(`strict`)는 다르다. `BLD-3-06` 이 그것을 다룬다.
  판정은 사람이 한다. 브랜치 보호는 저장소 설정이라 어느 파일에도 없고, 자동 리뷰는 diff 만 본다.
* `BLD-3-02` `main` 으로 가는 PR 의 출처를 `develop`, `release/*`, `hotfix/*` 로 제한하는가
  이 항목과 `BLD-3-04` 만 자동 리뷰가 본다. 게이트가 자기 가드를 지키지 못하고, 리뷰 설정이 꺼져도 아무것도 빨개지지 않기 때문이다.
  GitHub 의 브랜치 보호에는 base 를 제한하는 항목이 없고, 조직 룰셋은 Team 플랜부터 쓸 수 있다.
  그래서 판정을 `G-BUILD` 의 첫 스텝에 둔다. `github.base_ref` 가 `main` 일 때만 돌고 `github.head_ref` 를 본다.
  체크아웃 앞에 두어 출처가 틀리면 빌드를 시작하지 않고 떨어진다.
* `BLD-3-03` PR 게이트 트리거의 `branches` 에 `main` 과 `develop` 이 모두 있는가
  빠진 브랜치로 가는 PR 은 `G-BUILD` 가 돌지 않는다. 필수 상태 검사는 등록되어 있으므로 검사가 영원히 `pending` 으로 남아 병합이 막힌다.
  판정은 GitHub 이 한다. `pull_request` 이벤트는 PR 쪽 워크플로 파일로 돌므로, 트리거를 지우는 PR 은 그 PR 자신이 막힌다.
  자동 리뷰는 이 항목을 지적하지 않는다. 결정론적으로 막히는 것을 두 번 말하면 지적이 예산만 쓴다.
* `BLD-3-04` CodeRabbit 의 `reviews.auto_review.base_branches` 에 `develop` 이 있는가
  이 목록은 기본 브랜치 외에 리뷰할 base 를 적는 곳이다. 기본 브랜치(`main`)는 항상 리뷰되므로 적지 않는다.
  `develop` 을 빼면 기능 브랜치가 리뷰 없이 `develop` 에 들어간다.
  그렇게 누적된 변경이 `main` 으로 가는 PR 하나에 몰리면 지적 수가 상한에 닿아 뒤쪽이 잘려 나간다.
* `BLD-3-05` 자동 리뷰를 건너뛰는 수단을 두지 않았는가
  `ignore_title_keywords`, `labels`, `ignore_usernames`, `description_keyword` 가 그 수단이다.
  `develop` -> `main` 승격 PR 이 이미 리뷰된 커밋의 합이라 중복 리뷰를 받는 것은 사실이다. 그래도 끄지 않는다.
  필터는 base 와 head 를 함께 보지 못한다. `main` 쪽을 끄는 어떤 조건이든 `release/*` 와 `hotfix/*` 를 함께 끄고, 그 둘은 `develop` 을 거치지 않아 그 PR 이 유일한 리뷰 기회다.
  중복 리뷰의 비용은 지적이 상한에 닿아 뒤쪽이 잘리는 것이고, 끈 쪽의 비용은 가장 급한 변경이 아무 리뷰도 받지 않는 것이다. 두 비용이 같은 무게가 아니다.
* `BLD-3-06` `main` 의 최신 상태 요구(`strict`)를 껐는가. `develop` 은 켜 두는가
  GitHub 은 PR 을 머지 커밋으로 병합하고 그 커밋은 base 에만 생긴다. 승격이 끝나면 `main` 에만 있는 커밋이 하나 남는다.
  `main` 에 `strict` 가 켜져 있으면 다음 승격 PR 이 그 커밋을 품지 않아 `behind` 가 되고, 푸는 방법이 `main` 을 `develop` 에 머지하는 되병합이다.
  **승격 PR 에서 `strict` 는 하는 일이 없다.** `develop` 이 `main` 의 상위집합이라 합쳐진 상태가 곧 `develop` 이고 그것은 이미 검사됐다.
  파일 변경이 0인 PR 을 승격마다 하나 더 여는 것이 그 설정의 유일한 효과였다. 들여온 뒤 네 번 그렇게 했다.
  `develop` 은 켜 둔다. 기능 브랜치는 서로 모르는 채 갈라져 자라므로 합쳐진 상태를 검사해야 한다. 실제로 두 PR 이 같은 파일을 건드렸고 git 은 충돌 없이 합쳤다.
  대신 `hotfix/*` 와 `release/*` 는 `main` 에서 갈라져 `main` 으로 돌아오므로 합쳐진 상태 검사를 잃는다. 그 경로는 드물고, 끄지 않으면 승격마다 비용을 낸다. 이 교환을 택했다.
  판정은 사람이 한다. 브랜치 보호는 저장소 설정이라 어느 파일에도 없다.
* `BLD-3-07` `hotfix/*` 가 `main` 에 들어가면 `develop` 으로 되병합했는가
  `strict` 를 끈 뒤에도 이 되병합은 남는다. 성격이 다르기 때문이다.
  승격의 되병합은 커밋 하나를 옮기는 일이고 파일 변경이 0이었다. 핫픽스는 `develop` 에 없는 **내용**이 `main` 에 들어간 것이라, 되병합하지 않으면 다음 승격이 그 수정을 덮어쓴다.
  판정은 사람이 한다. 핫픽스를 넣은 사람이 이어서 한다.

## 4. 관련 문서


* 설계 근거: [build-gate-rationale.md](./build-gate-rationale.md)
* 패키지 구조: [런치캐치_도메인_구조와_의존성_설계.md](../architecture/런치캐치_도메인_구조와_의존성_설계.md) 1.3절
* 판정 대상 설정: 저장소 루트의 `build.gradle` 과 `.github/workflows/pr-gate.yml`
