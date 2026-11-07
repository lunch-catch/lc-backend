# 00:00 묶음 캠페인 네 단계의 규모 측정 정의

이 문서는 측정을 돌리기 전에 정한 것을 담는다. 무엇을 재는지, 어떤 데이터로 재는지, 어떻게 재는지, 무엇을 기록하는지다. 돌린 결과와 한계는 [midnight-scale-measure-result.md](midnight-scale-measure-result.md) 가 갖는다.

측정은 통합 테스트로 돌린다. 테스트는 회차가 오류 없이 끝나는지와 처리 건수가 같은지에만 단언을 건다. 소요 시간, 쿼리 수, 힙 사용은 기록만 하고 통과나 실패의 기준으로 쓰지 않는다.

## 1. 측정 대상

00:00 묶음 중 캠페인이 맡은 네 단계다. 서비스 메서드를 하나씩 직접 불러 잰다. 작업(Job) 클래스와 배치 점유, 스케줄러는 거치지 않는다.

| 단계 | 메서드 | 대상 |
|---|---|---|
| 종료 | `CampaignDailyTransitionService.endExpired` | 집행 기간이 끝난 ACTIVE, PAUSED 와 시작 전에 기간이 지난 SCHEDULED |
| 시작 | `CampaignDailyTransitionService.startScheduled` | 시작일이 된 SCHEDULED. 같은 가게의 기존 ACTIVE 는 먼저 종료한다 |
| 가게 요약 준비 | `CampaignStoreSummaryService.prepare` | 집행 기간이 끝나지 않은 ACTIVE |
| 하루 예산 변경 | `CampaignDailyTransitionService.applyPendingDailyBudgets` | 하루 예산 변경이 예약된 ACTIVE |

## 2. 측정용 데이터

테스트가 직접 만든다. JDBC 배치 INSERT 로 커밋된 행을 만들고, 난수는 씨앗 1091 로 고정한다. 같은 규모로 다시 만들면 같은 데이터가 나온다. 기준 영업일은 2026-11-10 이다.

규모는 캠페인 수다. 1,000건과 10,000건을 잰다. 구성 비율은 아래와 같고 합이 규모와 같다. 규모가 100의 배수가 아니면 나머지는 `RUNNING` 이 갖는다.

| 종류 | 비율 | 1,000건 | 10,000건 | 영업일에서의 모습 |
|---|---|---|---|---|
| `END_ACTIVE` | 15% | 150 | 1,500 | ACTIVE. 집행 기간이 어제 끝났다 |
| `END_SCHEDULED` | 5% | 50 | 500 | SCHEDULED. 시작 전에 집행 기간이 끝났다 |
| `START_PLAIN` | 10% | 100 | 1,000 | SCHEDULED. 오늘 시작한다. 같은 가게에 다른 캠페인이 없다 |
| `START_REPLACE` | 10% | 100 | 1,000 | SCHEDULED. 오늘 시작한다. 같은 가게에 ACTIVE 가 이미 있다 |
| `REPLACED_ACTIVE` | 10% | 100 | 1,000 | `START_REPLACE` 와 같은 가게의 기존 ACTIVE |
| `RUNNING` | 35% | 350 | 3,500 | ACTIVE. 계속 진행한다 |
| `PENDING_BUDGET` | 10% | 100 | 1,000 | ACTIVE. 계속 진행하고 하루 예산 변경이 예약돼 있다 |
| `PAUSED` | 5% | 50 | 500 | PAUSED. 계속 진행한다 |

단계마다 닿는 대상은 아래와 같다.

| 단계 | 대상 종류 | 1,000건 | 10,000건 |
|---|---|---|---|
| 종료 | `END_ACTIVE`, `END_SCHEDULED` | 200 | 2,000 |
| 시작 | `START_PLAIN`, `START_REPLACE` | 200 | 2,000 |
| 가게 요약 준비 | `REPLACED_ACTIVE`, `RUNNING`, `PENDING_BUDGET` | 550 | 5,500 |
| 하루 예산 변경 | `PENDING_BUDGET` | 100 | 1,000 |

가게는 `START_REPLACE` 와 `REPLACED_ACTIVE` 가 한 곳을 나누고, 나머지는 캠페인마다 하나씩 갖는다. 가게당 ACTIVE 는 하나라는 유일 키가 있어서 ACTIVE 캠페인끼리는 가게가 겹치지 않는다. 가게마다 메뉴를 하나 만들고, 캠페인의 절반은 `MENU` 할인으로 그 가게의 메뉴를 가리킨다.

점주, 가게, 메뉴, 캠페인은 이메일과 `notice` 의 접두어(`scale-measure-`)로 찾아 지운다. 앞선 실행이 도중에 죽어 행이 남았어도 다음 실행이 먼저 치운다.

## 3. 데이터 오류 비율

오류 캠페인은 할인 대상 메뉴를 다른 가게의 메뉴로 지정해 만든다. 가게 요약이 이 캠페인을 거부하면 `IllegalArgumentException` 이 나고, 시작 단계에서는 `StoreSummaryRejectedException` 으로 감싸져 그 캠페인만 건너뛴다.

오류 비율은 전체 캠페인 수에 대한 비율이다. 오류 캠페인 수는 `규모 × 비율` 을 반올림한 값이다.

| 오류 비율 | 1,000건 | 10,000건 |
|---|---|---|
| 0% | 0 | 0 |
| 0.1% | 1 | 10 |
| 1% | 10 | 100 |

오류는 `RUNNING`, `START_PLAIN`, `START_REPLACE` 중 `MENU` 할인 캠페인에서만 뽑는다. 종료 대상과 하루 예산 변경 대기에는 넣지 않는다. 이 측정이 보려는 것은 가게 요약 준비와 시작 단계가 데이터 오류를 건너뛰는 경로이기 때문이다.

뽑는 순서는 고정이라 0.1% 의 오류 캠페인은 1% 의 오류 캠페인에 모두 들어 있다. 오류가 시작 대상에 걸리면 그 캠페인은 ACTIVE 가 되지 못해 가게 요약 준비의 대상에서도 빠진다. 그래서 오류 캠페인 수와 가게 요약 준비가 건너뛴 수는 같지 않다.

## 4. 측정 방법

### 4.1 호출과 시간

네 단계를 서비스 메서드로 하나씩 직접 불러 단계별로 `System.nanoTime` 으로 잰다. 호출 앞뒤의 가비지 컬렉션 요청, 통계 읽기, 복원은 시간에 넣지 않는다.

### 4.2 처음 상태로 되돌리기

단계가 캠페인 상태를 바꾸므로 한 번 잴 때마다 처음 상태로 되돌린다. 되돌리는 시간은 재지 않는다. 되돌리는 대상은 아래와 같다.

- 캠페인의 상태 관련 컬럼(`status`, `status_changed_at`, `paused_reason`, `daily_budget`, `pending_daily_budget`, `updated_at`)
- 단계가 쌓은 `campaign_status_log`
- 단계가 쌓은 `store_campaign_summary`

그래서 모든 단계는 처음 상태에서 출발한 시간이다. 실제 배치처럼 앞 단계가 끝난 상태에서 이어서 도는 시간이 아니다. 예를 들어 가게 요약 준비는 시작 단계가 켠 캠페인을 포함하지 않은 상태로 잰다.

### 4.3 예열과 반복

한 회차가 네 단계를 한 번씩 돈다. 0번 회차는 예열이라 버리고, 그 뒤 `MEASURE_ROUNDS` 번(본 측정은 3번)을 남긴다. 조건(규모와 오류 비율)마다 따로 돈다. 결과는 중앙값과 범위(최솟값과 최댓값)로 적는다. 회차 수가 작아 백분위수는 의미가 없다.

### 4.4 기록 항목

단계마다 회차별로 아래를 기록한다.

| 항목 | 방법 |
|---|---|
| 처리 건수 | 메서드가 돌려준 값 |
| 초당 처리 건수 | 처리 건수를 소요 시간으로 나눈 값 |
| Hibernate 쿼리 수 | `Statistics.getPrepareStatementCount` 의 증가량 |
| 서버 쿼리 수 | MySQL `Com_select`, `Com_insert`, `Com_update`, `Com_delete` 합의 증가량 |
| 대상 한 건당 서버 쿼리 수 | 서버 쿼리 수를 위 대상 수로 나눈 값 |
| 힙 증가량 | 호출 직전 `System.gc` 뒤 사용 힙과, 호출 중 5ms 간격으로 읽은 사용 힙 최댓값의 차 |
| 가게 요약 준비의 청크 수 | 읽은 행(처리 + 건너뜀)을 500으로 나눈 올림 |
| 한 건씩 다시 저장한 청크 수 | `CAMPAIGN_DAILY_SUMMARY_CHUNK_RETRY` 로그의 수 |
| 건너뛴 캠페인 수 | `CAMPAIGN_DAILY_SUMMARY_SKIPPED` 로그의 수 |

가게 요약 저장은 `NamedParameterJdbcTemplate` 로 실행되어 Hibernate 통계가 세지 못한다. 그래서 서버 카운터를 함께 읽는다. 서버 카운터는 카운터를 읽는 조회 자신도 한 건씩 세지만 그 차이는 단계의 수천 건에 비해 무시한다.

테스트 JVM 의 최대 힙은 2GB 다. 힙이 모자라면 `OutOfMemoryError` 를 잡아 그 단계의 `_outOfMemory` 횟수로 기록하고 다음으로 넘어간다.

### 4.5 로그

재는 동안 Logback 레벨을 낮춘다. 캠페인 한 건마다 쓰는 DEBUG 로그가 시간에 섞이기 때문이다. 루트를 WARN 으로 두고 `CampaignDailyTransitionService` 는 끈다. `CampaignStoreSummaryService` 는 WARN 으로 두고 콘솔로 보내지 않으며, 그 로그만 따로 받아 한 건씩 다시 저장한 청크와 건너뛴 캠페인을 센다. 레벨은 런타임에 바꾸므로 Spring 컨텍스트 캐시 키에 영향이 없고, 끝나면 원래대로 돌린다.

## 5. 판정

회차마다 아래 세 가지에만 단언을 건다. 어긋난 회차가 있어도 그 자리에서 멈추지 않고, 모든 회차를 돈 뒤 한 번에 실패시킨다.

| 번호 | 불변식 |
|---|---|
| NO_EXCEPTION | 단계가 예외 없이 끝난다 |
| SAME_COUNT | 같은 조건에서 처리 건수가 회차마다 같다 |
| ALL_TARGETS | 오류 0% 에서 처리 건수가 위 대상 수와 같다 |

## 6. 실행 방법

`MEASURE_SCALE` 이 있을 때만 돈다. 없으면 클래스 전체가 건너뛰어져 `./gradlew check` 의 시간과 PR 게이트에 영향이 없다.

```
MEASURE_SCALE=1000,10000 MEASURE_ROUNDS=3 \
  ./gradlew integrationTest --rerun --tests "*CampaignMidnightScaleMeasureIntegrationTest"
```

Gradle 은 환경변수를 입력으로 보지 않는다. 같은 코드로 다시 돌릴 때는 `--rerun` 을 붙여야 한다. 컨테이너를 다른 통합 테스트와 함께 쓰므로 측정은 단독으로 돌린다.

결과 표는 표준 출력에 나오고, JSON 은 `build/measure/midnight-scale.json` 에 조건이 끝날 때마다 덮어쓴다. 측정이 끝나면 테스트가 넣은 행을 모두 지운다. 측정 전에 `CampaignScaleRestoreIntegrationTest` 를 같은 환경변수로 먼저 돌려 복원이 처음 상태와 같은지 확인한다.

## 7. 측정하지 않는 것

- 운영 환경의 부하와 처리량. 이 측정은 한 JVM 안에서 서비스 메서드를 부른다.
- 작업(Job) 클래스, 배치 점유, 스케줄러, 서버 두 대의 이어받기.
- 이벤트 리스너가 실제 행을 갱신하는 비용. 측정 데이터에는 광고 후보, 예산 계획, 쿠폰 이벤트, 찜 행이 없어서 리스너의 UPDATE 는 0건을 고치는 쿼리로 돈다.
