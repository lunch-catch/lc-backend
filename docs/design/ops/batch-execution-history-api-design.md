# 배치 실행 이력 조회 API 설계

| 항목 | 내용 |
|---|---|
| 근거 | 이슈 #570(명세), #575(구현). 요구사항명세서 102행 I102 "(작업명, 영업일)마다 실행 여부와 처리 건수, 실행한 서버를 조회할 수 있게 한다" |
| 명세서 | [admin-api-spec.md](../../api-spec/admin-api-spec.md#배치-실행-이력-조회) |
| 저장 구조 | `batch_execution_log`([ops V1](../../../src/main/resources/db/migration/ops/V1__ops_init.sql), V2 `retry_count`, V5 `attempt_no`). 유일 키 `(job_name, business_date)` |
| 관련 문서 | [batch-execution-retry-api-design.md](./batch-execution-retry-api-design.md), [런치캐치_배치_운영.md](./런치캐치_배치_운영.md) 3장 |

배치 수동 재실행(#362)은 접수만 하고 결과를 돌려주지 않는다. 관리자가 그 결과와 실패한 작업을 화면에서 확인할 길이 없었다. 분석의 A-06(`GET /v1/admin/aggregation-runs`)은 `DAILY_AGGREGATION` 하나만 보여 준다. 이 API 가 운영 전역의 실행 기록을 연다.

## 서버가 하는 일

1. `from`, `to` 를 확인한다. 없거나 `to` 가 `from` 보다 앞이거나 31일을 넘으면 `400 OPS-004` 다.
2. `pageToken` 이 있으면 풀어서 마지막으로 내준 (`businessDate`, `runId`)를 얻는다. 풀 수 없으면 `400 COMMON-002` 다.
3. `batch_execution_log` 에서 영업일 범위와 `jobName`, `status` 조건에 맞는 행을 `business_date DESC, batch_execution_log_id DESC` 로 `pageSize + 1` 개 읽는다. 하나 더 읽혔으면 다음 페이지가 있다.
4. 행마다 `retryable` 을 계산한다. `status == FAILED` 이고 `DailyJobRunner.isRetryable(jobName)` 이 참이면 `true` 다.
5. 시각은 `+09:00` 을 붙여 내보낸다.

ops 의 service 가 `BatchExecutionLogRepository` 를 바로 읽는다. `ops.contract.BatchExecutionQueryService` 는 다른 도메인(분석, 정산)에 공개한 contract 이다. ops 가 자기 데이터를 읽으려고 그 contract 를 거칠 이유가 없고, 그 contract 에는 영업일 범위 조회 메서드도 없다.

## 응답 필드와 저장 구조

필드 이름은 `BatchExecutionRecord` 와 A-06 의 이름을 그대로 쓴다.

| 응답 필드 | 컬럼 | 비고 |
|---|---|---|
| `runId` | `batch_execution_log_id` | |
| `jobName` | `job_name` | |
| `businessDate` | `business_date` | |
| `status` | `status` | `chk_batch_status` 가 세 값으로 묶는다 |
| `startedAt` | `created_at` | 수동 재실행과 분석의 재실행이 새로 쓴다(#359) |
| `finishedAt` | `finished_at` | `chk_batch_finished` 가 `RUNNING` 일 때만 `NULL` 로 묶는다 |
| `processedCount` | `processed_count` | 재실행하면 `NULL` 로 비운다 |
| `executedBy` | `owner_id` | |
| `retryCount` | `retry_count` | `chk_batch_retry` 가 0~2 로 묶는다 |
| `failureReason` | `failure_reason` | `chk_batch_failure_reason` 이 `FAILED` 일 때만 값이 있게 묶는다 |
| `attemptNo` | `attempt_no` | `chk_batch_attempt` 가 1 이상으로 묶는다 |
| `retryable` | 없음 | 저장하지 않고 응답할 때 계산한다 |

## 정한 이유

**결정: "이력" 을 (작업명, 영업일)마다 지금 상태 한 행으로 볼지, 시도마다 따로 남길지**

| 선택지 | 장점 | 단점 |
|---|---|---|
| A. 지금 상태 한 행을 보여 준다 | 저장 구조와 마이그레이션이 그대로다. 재실행했다는 사실은 `attemptNo` 와 `retryCount` 로 보인다 | 지난 시도의 시각과 실패 사유는 볼 수 없다 |
| B. 시도마다 행을 남기는 이력 표를 새로 둔다 | 시도별 실패 사유와 시각을 모두 본다 | 마이그레이션이 필요하다. 상태가 바뀌는 자리마다(점유, 이어받기, 재실행, 닫기) 이력 쓰기가 붙는다 |

A 를 고른다. `batch_execution_log` 는 `restartFailed` 와 `restartFinished` 가 같은 행을 새 시도로 덮어쓴다. I102 가 요구하는 것은 (작업명, 영업일)마다의 실행 여부, 처리 건수, 실행한 서버라 A 로 충족된다.

**결정: 영업일 조건을 하루로 받을지, 범위로 받을지**

| 선택지 | 장점 | 단점 |
|---|---|---|
| A. `businessDate` 하나 | A-06 과 같은 모양이다 | 여러 날의 실패를 보려면 날마다 따로 불러야 한다 |
| B. `from`, `to` 범위(필수, 최대 31일) | 지난 며칠의 실패를 한 번에 찾는다. 하루만 보려면 `from = to` 로 부른다 | 범위 상한을 정해야 하고 조회 쿼리가 새로 생긴다 |

B 를 고른다. 관리자가 수동 재실행할 대상을 고르는 데 쓰는 API 라 여러 날을 한 번에 봐야 한다. 31일은 근거 없는 예시값(C)이다. 분석의 A-09, A-10 이 같은 상한을 쓴다.

**결정: `jobName` 을 정확히 일치로만 받는다.** 캠페인 단위 작업은 `EXPIRE_COUPON:42` 처럼 캠페인마다 이름이 갈린다. 앞부분 일치를 받으면 와일드카드 문자와 이스케이프 규칙을 계약에 넣어야 한다. 앞부분 일치는 나중에 더해도 기존 호출이 깨지지 않는다.

**결정: 공통 규약의 커서 방식을 쓰고 정렬을 고정한다.** 캠페인 단위 작업이 있어 하루 행 수에 상한이 없다. 커서는 마지막 항목의 (`businessDate`, `runId`)다. 정렬 키가 둘 다 바뀌지 않는 값이라 페이지를 넘기는 동안 새 실행이 생겨도 항목이 겹치거나 밀리지 않는다. 새로 생긴 행은 그 영업일 안에서 가장 큰 `runId` 라 그 영업일의 맨 앞에 놓인다. 이미 넘긴 영업일에 생긴 행은 그 페이지 넘김에서는 보이지 않고 다시 처음부터 조회하면 보인다.

**결정: `retryable` 을 응답에 넣는다.** 재실행할 수 있는 작업명은 00:00 묶음과 등록된 묶음 밖 작업뿐이고 그 등록 목록은 서버만 안다. 넣지 않으면 화면은 `FAILED` 인 개별 단계에도 재실행 버튼을 띄우고 `422 OPS-001` 을 받는다.

**결정: 기간 위반에 `OPS-004` 를 새로 둔다.** 화면이 "기간이 잘못됐다" 를 다른 형식 오류와 따로 안내할 수 있게 한다. 분석의 `ANALYTICS-001` 과 같은 방식이다. 날짜 형식, `status` 값, `pageSize` 범위, `pageToken` 해석 실패는 공통 규약대로 `COMMON-002` 다.

## 성능

| 적을 것 | 값 |
|---|---|
| 요청 하나의 비용 | DB 읽기 1(조건 조회, `pageSize + 1` 행). `retryable` 은 메모리 조회 |
| 목표 | 관리자가 누르는 조회라 목표 TPS 를 두지 않는다. 응답 시간 목표는 p95 500ms 다(근거 없는 예시값) |
| 확인 방법 | #575 의 통합 테스트로 조회 결과만 본다. 응답 시간은 재지 않는다 |

`business_date` 를 앞에 둔 인덱스가 없다. 유일 키는 `(job_name, business_date)` 라 `jobName` 이 없는 조회는 표 전체를 훑는다. 아래 "아직 정해지지 않은 것" 에 남긴다.

## 다른 도메인에 공개한 기능

없다. `ops` 내부 기능이다.

## 아직 정해지지 않은 것

| 무엇이 안 정해졌나 | 누가 정하나 | 정해지면 어디로 가나 |
|---|---|---|
| `business_date` 인덱스를 더할지. 없으면 `jobName` 없는 조회가 표 전체를 훑는다. 하루 행 수가 작업 수와 캠페인 수에 달려 있어 지금은 판단할 값이 없다 | #575 구현자가 행 수를 보고 정한다. 더하면 ops 마이그레이션이 먼저다 | 이 문서의 "성능" 절 |
| `pageToken` 의 인코딩 모양. 계약은 "서버가 준 값을 그대로 돌려보낸다" 뿐이라 화면에 영향이 없다 | #575 구현자 | 이 문서의 "서버가 하는 일" 절 |
| `retryReason`(마지막 자동 재실행 사유)과 `updatedAt`(마지막 생존 신호)을 응답에 넣을지. 넣지 않으면 실행 중인 작업이 멈췄는지 화면이 알 수 없다 | 관리자 화면 기획이 필요하다고 할 때 ops 담당이 정한다 | [admin-api-spec.md](../../api-spec/admin-api-spec.md#배치-실행-이력-조회) 응답 필드 표 |
| 시도별 이력. 지금은 지난 시도의 실패 사유가 남지 않는다 | ops 담당. 필요해지면 새 이슈로 연다 | 이 문서의 "정한 이유" 절과 ops 마이그레이션 |
| 요구사항 I102 의 기능 ID(FR 번호) | ops 담당 | [api-spec.md](../../api-spec/api-spec.md) 의 "도메인과 문서" 표 |
