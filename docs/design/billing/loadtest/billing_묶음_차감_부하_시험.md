# 묶음 차감 부하 시험

노출 릴레이(`ImpressionDispatchJob`)가 정산에 넘기는 묶음 차감(`PointDeductorImpl.deductAll`)이 왜 느린지 본다. 이 문서는 `point_ledger` 의 인덱스 수가 원인이라는 가설을 확인하는 시험을 갖는다(#1228).

부하는 adserving 의 노출 수집 시험을 그대로 쓴다. 시나리오와 실행 스크립트는 `loadtest/adserving/` 이 갖고, 환경을 띄우고 내리는 일은 lc-infra 의 `scripts/member-env.sh` 가 갖는다.

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

```sql
ALTER TABLE point_ledger
  DROP INDEX idx_point_ledger_owner_business_date,
  DROP INDEX idx_point_ledger_owner_created;

ALTER TABLE point_ledger
  ADD INDEX idx_point_ledger_owner_business_date (owner_id, business_date),
  ADD INDEX idx_point_ledger_owner_created (owner_id, created_at);
```

## 3. 회차

회차를 이어 돌리면 `impression_log`, `serve_log`, `deduction_result`, `point_ledger` 가 쌓여 뒤 회차일수록 느려진다. 그래서 A(인덱스 있음)와 B(지움)를 A, B, B, A 순서로 돌려 쌓이는 효과를 양쪽에 똑같이 나눈다.

| 순서 | 회차 이름 | 인덱스 | 회원 시작 번호 |
|---|---|---|---|
| 1 | `abba-warmup` | 있음 | 30090001 |
| 2 | `abba-a1` | 있음 | 30000001 |
| 3 | `abba-b1` | 지움 | 30010001 |
| 4 | `abba-b2` | 지움 | 30020001 |
| 5 | `abba-a2` | 있음 | 30030001 |

- 부하는 `run.sh run` 의 기본값이다. 초당 피드 100, 램프업 60초, 유지 5분이다.
- 회차마다 새 회원 대역을 써서 `reset` 하지 않는다. 11-11 에는 `reset` 의 대량 DELETE 가 15분 걸렸고 그 뒤 환경이 무너졌다.
- 회차 사이에 Valkey 를 비우지 않는다.
- 측정 회차에는 프로파일러를 붙이지 않는다. 프로파일러는 비율을 보는 진단이고 시간을 늘린다.
- 인덱스를 바꾼 뒤 앱을 다시 띄우지 않는다.

## 4. 돌리는 순서

1. `member-env.sh up` 으로 환경을 띄운다.
2. `run.sh seed 120000` 으로 회원 120,000명(30000001 ~ 30120000)과 캠페인을 넣는다.
3. `run.sh prepare` 로 그날 00:00 묶음을 만든다.
4. 3장의 표 순서대로 `run.sh run <회차 이름> <회원 시작 번호>` 를 돌린다. B 앞에서 인덱스를 지우고 A2 앞에서 되살린다.
5. 회차마다 `run` 이 출력한 구간으로 `run.sh metrics <시작 ms> <끝 ms>` 를 묻는다.

`run` 은 회차마다 경고 로그를 이 문서의 7장에 적고 지금 브랜치에 커밋한다. `RESULT_DOC=docs/design/billing/loadtest/billing_묶음_차감_부하_시험.md` 를 주고 돌린다. 주지 않으면 adserving 의 결과 문서에 적는다.

## 5. 판정

| 지표 | 쓰는 곳 |
|---|---|
| `relay_deduct_avg_s` | 묶음 차감 한 번의 평균 시간. 주 지표다 |
| `relay_deduct_rows_per_s` | 초당 차감한 행 수 |
| `rds_CPUUtilization` | 회차 구간의 RDS CPU 최대 |

A 두 회차의 차이를 잡음의 크기로 본다. A 평균과 B 평균의 차이가 그보다 충분히 크면 인덱스의 효과로 읽는다. A 두 회차의 차이가 B 와의 차이만큼 크면 이 시험으로는 효과를 가르지 못한 것이다.

## 6. 결과

## 7. 경고 로그

회차마다 `run` 이 그 회차 구간의 `ADSERVING_` 경고와 오류를 이벤트별로 세어 이 절의 표에 적는다. 이름이 `-warmup` 으로 끝나는 회차는 적지 않는다.
