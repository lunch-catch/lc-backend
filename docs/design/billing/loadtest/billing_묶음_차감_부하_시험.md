# 묶음 차감 부하 시험

노출 릴레이(`ImpressionDispatchJob`)가 정산에 넘기는 묶음 차감(`PointDeductorImpl.deductAll`)이 왜 느린지 본다. 이 문서는 `point_ledger` 의 인덱스 수가 원인이라는 가설을 확인하려 한 시험을 갖는다(#1228).

**2026-11-13 회차는 판정하지 못했다.** 노출 API 부하와 함께 회차를 이어 돌리자 회차마다 환경이 가속해 나빠져, 인덱스의 효과를 회차 순서의 효과와 가를 수 없었다. 이유와 숫자는 6장에 있다. 다음 측정은 노출 API 부하 없이 대기 노출만 비우는 시험(#1198)으로 한다.

부하는 adserving 의 노출 수집 시험을 그대로 썼다. 시나리오와 실행 스크립트는 `loadtest/adserving/` 이 갖고, 환경을 띄우고 내리는 일은 lc-infra 의 `scripts/member-env.sh` 가 갖는다.

## 1. 가설

`deductAll` 시간의 약 71% 가 `point_ledger` 의 DEDUCT INSERT 다(2026-11-11 프로파일링). 잠금 대기, 디스크 읽기, 빈 페이지 대기, redo 로그 대기, 커넥션 풀은 원인이 아니었다.

`point_ledger` 에는 PK 를 포함해 B-tree 가 12개 있고 DEDUCT 한 행마다 모두 고친다. 인덱스가 데이터의 3.5배이고, RDS(`db.t4g.micro`)의 버퍼 풀은 128MB 다. 그래서 인덱스를 줄이면 INSERT 가 빨라진다고 본다.

## 2. 지우는 인덱스

12개 가운데 지워도 정합성이 깨지지 않는 것은 둘뿐이다. 나머지는 멱등이나 FK 에 걸려 있다.

| 인덱스 | 지워도 되는 이유 |
|---|---|
| `idx_point_ledger_owner_business_date` `(owner_id, business_date)` | DEDUCT 행은 `business_date` 가 NULL 이다. 이 인덱스를 쓰는 조회는 모두 `entry_type` 도 거르므로 `idx_point_ledger_owner_type_created` 가 받는다 |
| `idx_point_ledger_owner_created` `(owner_id, created_at)` | 점주 조건은 `idx_point_ledger_owner_type_created` 의 앞부분이 받는다. 운영에 넣으려면 잔액과 원장 조회를 EXPLAIN 으로 확인해야 한다 |

이 시험은 효과의 크기만 재고, 인덱스를 운영에서 지울지는 정하지 않는다.

`V14__billing_stats_date_indexes.sql`(#1241) 부터는 `idx_point_ledger_owner_business_date` 가 `idx_point_ledger_date_type (business_date, entry_type)` 로 바뀌었다. 인덱스 수는 그대로라 다음 회차의 B 는 `idx_point_ledger_date_type` 와 `idx_point_ledger_owner_created` 를 지운다. `idx_point_ledger_date_type` 는 분석 집계의 예약과 해제 합계가 쓰므로 회차가 끝나면 되살린다.

```sql
ALTER TABLE point_ledger
  DROP INDEX idx_point_ledger_owner_business_date,
  DROP INDEX idx_point_ledger_owner_created;

ALTER TABLE point_ledger
  ADD INDEX idx_point_ledger_owner_business_date (owner_id, business_date),
  ADD INDEX idx_point_ledger_owner_created (owner_id, created_at);
```

## 3. 시도한 회차

회차를 이어 돌리면 `impression_log`, `serve_log`, `deduction_result`, `point_ledger` 가 쌓여 뒤 회차일수록 느려진다(11-11 에도 인덱스를 그대로 둔 채 프로파일링 회차 뒤에 돌린 기준 회차가 1.92초에서 5.42초로 느려졌다). 그래서 A(인덱스 있음)와 B(지움)를 A, B, B, A 순서로 돌려 쌓이는 효과를 양쪽에 똑같이 나누려 했다. 이 순서는 나빠지는 폭이 회차마다 일정할 때만 효과를 상쇄한다.

| 순서 | 회차 이름 | 인덱스 | 회원 시작 번호 | 돌렸나 |
|---|---|---|---|---|
| 1 | `abba-warmup` | 있음 | 30090001 | 돌렸다 |
| 2 | `abba-a1` | 있음 | 30000001 | 돌렸다 |
| 3 | `abba-b1` | 지움 | 30010001 | 돌렸다 |
| 4 | `abba-b2` | 지움 | 30020001 | 돌렸다 |
| 5 | `abba-a2` | 있음 | 30030001 | 돌리지 않았다. b2 에서 환경이 무너져 결과를 판정에 쓸 수 없었다 |

- 부하는 `run.sh run` 의 기본값이다. 초당 피드 100, 램프업 60초, 유지 5분이다.
- 회차마다 새 회원 대역을 써서 `reset` 하지 않았다. 11-11 에는 `reset` 의 대량 DELETE 가 15분 걸렸고 그 뒤 환경이 무너졌다.
- 회차 사이에 Valkey 를 비우지 않았다. 측정 회차에는 프로파일러를 붙이지 않았다. 인덱스를 바꾼 뒤 앱을 다시 띄우지 않았다.

## 4. 돌린 순서

1. `member-env.sh up` 으로 환경을 띄웠다. 앱 2대, 배치 2대, 앱 이미지 develop 648295d7 이다.
2. `run.sh seed 120000` 으로 회원 120,000명(30000001 ~ 30120000)과 캠페인 1,000개를 넣었다.
3. `run.sh prepare` 로 그날 00:00 묶음을 만들었다. 처음에는 실패했다(아래).
4. 3장의 표 순서대로 `run.sh run <회차 이름> <회원 시작 번호>` 를 돌렸다. b1 앞에서 인덱스를 지웠다.
5. 회차마다 `run` 이 출력한 유지 구간으로 `run.sh metrics <시작 ms> <끝 ms>` 를 물었다.

`run` 은 회차마다 경고 로그를 이 문서의 7장에 적고 지금 브랜치에 커밋한다. `RESULT_DOC=docs/design/billing/loadtest/billing_묶음_차감_부하_시험.md` 를 주고 돌린다. 주지 않으면 adserving 의 결과 문서에 적는다.

**`prepare` 가 앱 서버의 5초 상한에 걸렸다.** 관리자 재실행 API 는 00:00 묶음을 앱 서버에서 돌린다. 앱 서버는 #1221 부터 쿼리와 트랜잭션에 5초 상한이 있다. 후보 예산 갱신(`AdCandidateLoadImpl.updateBudgets`)이 5초를 넘겨 `QueryTimeoutException` 으로 끝났다. `run.sh switch 648295d7 --env SPRING_TRANSACTION_DEFAULTTIMEOUT=-1s --env SPRING_JDBC_TEMPLATE_QUERYTIMEOUT=2m` 로 상한을 배치 서버와 같게 풀고 `prepare` 를 돌린 뒤, `--env` 없이 다시 `switch` 해 측정 회차는 원래 상한으로 돌렸다.

## 5. 판정 기준

| 지표 | 쓰는 곳 |
|---|---|
| `relay_deduct_avg_s` | 묶음 차감 한 번의 평균 시간. 주 지표다 |
| `relay_deduct_rows_per_s` | 초당 차감한 행 수 |
| `rds_CPUUtilization` | 회차 구간의 RDS CPU 최대 |

A 두 회차의 차이를 잡음의 크기로 본다. A 평균과 B 평균의 차이가 그보다 충분히 크면 인덱스의 효과로 읽는다. A 두 회차의 차이가 B 와의 차이만큼 크면 이 시험으로는 효과를 가르지 못한 것이다.

## 6. 결과 (2026-11-13)

**판정하지 못했다.** 차감 시간이 회차마다 두세 배씩 늘었다. 인덱스 조건이 같은 b1 과 b2 도 2.5배 차이가 난다.

| 지표 | a1 (있음) | b1 (지움) | b2 (지움) |
|---|---|---|---|
| 묶음 차감 평균 (`relay_deduct_avg_s`) | 4.36초 | 11.68초 | 29.12초 |
| 초당 차감 건수 (`relay_deduct_rows_per_s`) | 119 | 28 | 14 |
| 밀린 노출 최대 (`impression_dispatch_backlog_max`) | 143,071 | 649,726 | 1,064,720 |
| 커넥션 대기 최대 | 14 | 8 | 87 |
| 커넥션 시간 초과 | 0 | 0 | 202 |
| 커넥션을 쥔 평균 | 59ms | 62ms | 111ms |
| 초당 커밋 | 202 | 204 | 154 |
| RDS CPU (1분 최대) | 39 ~ 57% | 47 ~ 55% | 42 ~ 45% |
| RDS CPU 크레딧 최소 | 5.0 | 0 | 0 |
| Valkey CPU 최대 | 18% | 19% | 18% |
| 버린 서빙 기록 | 0 | 0 | 137,958 |
| 버린 노출 (저장 큐가 넘침) | 56,890 | 106,285 | 247,456 |
| 피드 실패율 | 0% | 0% | 0.66% |
| 피드 p99 | 544ms | 553ms | 2.08s |

서빙한 카드는 세 회차 모두 약 30만 장이라 들어온 부하는 같다.

1. **밀린 노출이 다음 회차로 넘어갔다.** 차감은 초당 119건 이하인데 노출은 초당 900건 넘게 들어왔다. a1 이 끝날 때 14만 건이 남았고 b1 과 b2 는 그 위에서 시작해 106만 건까지 쌓였다. 다음 회차는 더 많은 데이터와 더 많은 밀림 위에서 시작해 차감이 더 느려졌다.
2. **나빠지는 폭이 일정하지 않고 가속했다.** A, B, B, A 순서는 그 폭이 일정해야 상쇄한다. 그래서 a2 를 돌려도 가장 나쁜 조건의 a2 가 가장 불리해 효과를 읽을 수 없었다.
3. **DB 는 CPU 가 아닌 것을 기다렸다.** b2 는 처리량이 줄었는데 RDS CPU 는 45% 안팎으로 내려갔다. 쌓인 테이블과 인덱스가 128MB 버퍼 풀을 넘고 밀린 노출 100만 건을 고르는 일이 겹친 것으로 본다. 무엇을 기다렸는지는 확인하지 않았다.
4. **Valkey 는 원인이 아니었다.** CPU, 크레딧, 네트워크 모두 여유가 있었다. b2 의 Valkey 쓰기 실패(서빙 기록, 노출 빈도, 노출 수 각 2,500건 남짓)는 앱 쓰레드가 DB 커넥션을 기다리며 묶인 데서 번진 것으로 본다.
5. **차감 코드는 지운 인덱스를 읽지 않는다.** `PointDeductorImpl` 은 원장에 INSERT 만 한다. 인덱스를 지워 느려질 이유가 없으므로 a1 과 b 둘의 차이는 회차 순서의 효과로 읽는다.

### 다음 측정

노출 API 부하와 섞어 회차를 이어 도는 방식으로는 차감의 비용을 따로 잴 수 없다. 11-11 과 이번 두 번 같은 이유로 실패했다.

- 회차마다 같은 수의 대기 노출만 새로 넣고 릴레이가 비우는 속도를 잰다(#1198). 노출 API 를 부르지 않아 밀림이 다음 회차로 넘어가지 않고, 쌓이는 데이터도 회차마다 같아 A, B, B, A 순서가 성립한다.
- 그 시험으로는 인덱스 두 개를 지운 효과보다 원장에 DEDUCT 를 쓰지 않을 때의 효과를 먼저 잴 만하다. 이번 결과는 노출마다 원장에 한 줄씩 쌓이는 비용이 데이터가 커질수록 가파르게 커질 수 있다는 것을 보여 준다.

## 7. 경고 로그

회차마다 `run` 이 그 회차 구간의 `ADSERVING_` 경고와 오류를 이벤트별로 세어 이 절의 표에 적는다. 이름이 `-warmup` 으로 끝나는 회차는 적지 않는다.

| 회차 | 수준 | 이벤트 | 건수 |
|---|---|---|---|
| abba-a1 | - | 없음 | 0 |
| abba-a1 | 수집 | APP_SCRAPE_MISSED | 0 |
| abba-a1 | 수집 | BATCH_SCRAPE_MISSED | 0 |
| abba-b1 | ERROR | ADSERVING_IMPRESSION_WRITE_FAILED | 4 |
| abba-b1 | 수집 | APP_SCRAPE_MISSED | 0 |
| abba-b1 | 수집 | BATCH_SCRAPE_MISSED | 0 |
| abba-b2 | WARN | ADSERVING_FEED_SERVE_RECORD_WRITE_FAILED | 2771 |
| abba-b2 | WARN | ADSERVING_FEED_SERVE_FREQUENCY_WRITE_FAILED | 2587 |
| abba-b2 | WARN | ADSERVING_FEED_IMPRESSION_COUNT_INCREMENT_FAILED | 2489 |
| abba-b2 | ERROR | ADSERVING_IMPRESSION_WRITE_FAILED | 48 |
| abba-b2 | ERROR | ADSERVING_FEED_SERVE_LOG_WRITE_FAILED | 3 |
| abba-b2 | ERROR | ADSERVING_FEED_VISIT_WRITE_FAILED | 2 |
| abba-b2 | WARN | ADSERVING_IMPRESSION_BACKLOG_COUNT_FAILED | 1 |
| abba-b2 | 수집 | APP_SCRAPE_MISSED | 0 |
| abba-b2 | 수집 | BATCH_SCRAPE_MISSED | 0 |
