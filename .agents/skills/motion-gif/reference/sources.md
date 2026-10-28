# 어디를 읽는가

장면의 사실은 코드가 기준이다. 문서는 코드를 찾아가는 길로 쓰고 둘이 다르면 코드를 따른다.
자바 패키지는 `src/main/java/com/lunchcatch/` 아래다. 아래 표의 경로는 그 아래부터 적는다.

## 먼저 최신으로 맞춘다

```bash
git fetch origin develop
git log --oneline -15 origin/develop
```

최근 병합이 그리려는 주제를 바꿨는지 본다. 바뀌었으면 `git show <커밋>` 으로 무엇이 바뀌었는지 읽는다.
작업 브랜치가 develop 보다 뒤처져 있으면 develop 의 코드를 기준으로 읽는다(`git show origin/develop:<경로>`).

## lc-infra 를 찾는다

인프라를 그릴 때는 lc-infra 가 필요하다. lc-backend 와 같은 폴더에 `lc-infra` 가 있으면 그것을 쓴다. 없으면 받는다. 공개 저장소라 인증 없이 된다.

```bash
git clone --depth 1 https://github.com/lunch-catch/lc-infra .slides/lc-infra
```

## 주제별 시작점

| 주제 | lc-backend 에서 먼저 읽는 곳 | lc-infra 에서 먼저 읽는 곳 |
|---|---|---|
| 도메인 경계와 계층 | `docs/architecture/런치캐치_도메인_구조와_의존성_설계.md` 1장, 2장 | |
| 한 도메인의 내부 | `docs/design/{도메인}/` 의 구조와 동작 설계서, `{도메인}/` 의 패키지 | |
| 피드 배정 | `adserving/engine/FeedAssembler.java`, `AllocationOrder.java`, `engine/filter/`, `engine/score/` | |
| 그날 후보 | `adserving/engine/service/CandidateCache.java`, `engine/repository/CandidateChangeNotifier.java`, `engine/job/LoadDailyCandidateJob.java` | |
| 피드 요청 | `adserving/feed/service/FeedService.java`, `MemberInfoCache.java`, `ServeLogBuffer.java`, `feed/repository/ServeRecordRepository.java` | |
| 노출 판정과 저장 | `adserving/impression/service/ImpressionService.java`, `ImpressionWriteBuffer.java`, `ImpressionDispatchService.java`, `impression/job/ImpressionDispatchJob.java` | |
| 요청 중 쓰기의 버전 | `docs/design/adserving/adserving_요청_중_쓰기_설계.md` | |
| 정산 차감 | `billing/contract/PointDeductor.java`, `billing/service/` 의 차감 서비스 | |
| 선착순 쿠폰 발급 | `docs/design/coupon/` 의 발급 설계서 중 번호가 가장 큰 것, `coupon/service/CouponIssueBatcher.java`, `CouponCommandService.java`, `CouponErrorCode.java`, `src/main/resources/application.yml` 의 `coupon.issue`, `coupon.daily-limit`. 건수가 필요하면 `docs/troubleshooting/coupon/` 의 부하 시험 결과 | |
| 00:00 배치 | `ops/job/DailyJobScheduler.java`, `ops/scheduler/DailyJob.java`, `ops/service/BatchExecutionService.java`, `BatchJobRunner.java`, 각 도메인 `job/` 의 `@Order` 값, `docs/design/ops/런치캐치_배치_운영.md` | `terraform/compute.tf` 의 배치 ASG |
| 부하 시험 결과 | `docs/troubleshooting/{도메인}/` 의 부하 시험 결과, `docs/design/{도메인}/loadtest/` | |
| API 서버 확장 | | `terraform/compute.tf` 의 `aws_autoscaling_group.app`, `aws_autoscaling_policy`, `terraform/variables.tf` |
| 배포 | `docs/workflow/` | `README.md` 의 동작 흐름, `docs/deploy/README.md` |
| 장애 알림 | | `README.md` 의 장애 알림, `terraform/alarms.tf` |
| 데이터 저장소 | `src/main/resources/db/migration/` | `terraform/rds.tf`, `terraform/cache.tf` |

"몇 건", "얼마나 빨라졌나" 처럼 양을 묻는 요청은 코드만으로 답할 수 없다. 부하 시험 결과나 운영 지표 문서를 찾고, 없으면 예시 값임을 밝히거나 사용자에게 묻는다.

lc-infra 의 주석과 lc-backend 의 코드가 다르게 설명하면 코드를 따르고, 다른 점을 3단계 요약에 적는다.

표에 없는 주제는 `grep -rn` 으로 클래스 이름과 설계서를 찾는다. 같은 주제의 설계서가 버전별로 여럿이면(`-v1`, `-v2`) 번호가 가장 큰 것이 지금의 설계다. 그래도 코드와 대조한다.

## 숫자를 옮길 때

- 상수는 정의된 자리를 찾아 그 값을 쓴다. 주석에 적힌 값과 코드의 값이 다르면 코드를 쓴다.
- 설정값은 `application.yml` 과 Terraform 변수의 기본값을 쓴다. 환경 변수로 덮을 수 있다는 것은 장면에 적지 않는다.
- 부하 시험의 수치는 결과 문서의 표를 그대로 쓰고, 측정 회차와 날짜를 장면 아래 한 줄로 적는다.
- 예시 값(캠페인 번호, 곡선의 모양 같은 것)을 쓰면 장면 안에 예시라고 적는다.
- 코드를 장면에 옮길 때는 실제 줄을 쓴다. 줄이 길면 인자를 `...` 로 줄이되 뜻을 바꾸지 않는다.
