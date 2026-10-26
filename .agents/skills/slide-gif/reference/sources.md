# 어디를 읽는가

장면의 사실은 코드가 기준이다. 문서는 코드를 찾아가는 길로 쓰고, 둘이 다르면 코드를 따른다.

## lc-infra 를 찾는다

오토스케일링처럼 인프라를 그릴 때는 lc-infra 가 필요하다. 아래 순서로 찾는다.

1. lc-backend 와 같은 폴더에 `lc-infra` 가 있으면 그것을 쓴다. `../lc-infra`
2. 없으면 받는다. 저장소가 공개라 인증 없이 된다.

```bash
git clone --depth 1 https://github.com/lunch-catch/lc-infra .slides/lc-infra
```

## 주제별 시작점

| 주제 | lc-backend 에서 먼저 읽는 곳 | lc-infra 에서 먼저 읽는 곳 |
|---|---|---|
| 도메인 경계와 계층 | `docs/architecture/런치캐치_도메인_구조와_의존성_설계.md` 1장 | |
| 한 도메인의 내부 | `docs/design/{도메인}/` 의 구조와 동작 설계서, `src/main/java/com/launchcatch/{도메인}/` 의 패키지 | |
| 피드 배정 | `adserving/engine/FeedAssembler.java`, `AllocationOrder.java`, `engine/filter/`, `engine/score/` | |
| 노출 검증과 과금 | `adserving/impression/service/ImpressionService.java`, `adserving/feed/repository/ServeRecordRepository.java`, `billing/` 의 차감 서비스 | |
| 선착순 쿠폰 발급 | `docs/design/coupon/` 의 발급 설계서 중 번호가 가장 큰 것(지금은 `coupon-issuance-v3-design.md`), `coupon/service/CouponIssueBatcher.java`, `CouponCommandService.java`, `CouponSequenceService.java`, `application.yml` 의 `coupon.issue` | |
| 00:00 배치 | `ops/job/DailyJobScheduler.java`, `ops/scheduler/DailyJob.java`, 각 도메인 `job/` 의 `@Order` 값 | |
| 배치 운영 | `docs/design/ops/런치캐치_배치_운영.md` | `terraform/compute.tf` 의 배치 ASG |
| API 서버 확장 | | `terraform/compute.tf` 의 `aws_autoscaling_group.app` 과 `aws_autoscaling_policy`, `terraform/variables.tf` |
| 배포 | `docs/workflow/` | `README.md` 의 동작 흐름, `docs/deploy/README.md` |
| 장애 알림 | | `README.md` 의 장애 알림, `terraform/alarms.tf` |
| 데이터 저장소 | `src/main/resources/db/migration/` | `terraform/rds.tf`, `terraform/cache.tf` |

같은 주제의 설계서가 버전별로 여럿이면(`-v1`, `-v2`) 번호가 가장 큰 것이 지금의 설계다. 그래도 코드와 대조한다.

## 숫자를 옮길 때

- 상수는 정의된 자리를 찾아 그 값을 쓴다. 예를 들어 관련성 가중치는 `FeedAssembler` 의 주석과 각 `Signal` 의 `weight()` 를 함께 본다.
- 주석의 실측값(기동 160초 같은 것)은 측정한 날짜와 함께 적혀 있다. 그 값을 쓰고 `sources` 에 파일을 남긴다.
- 예시 값이 필요하면(캠페인 번호, 요청 수 같은 것) 장면 아래 `label` 에 예시라고 적는다.
