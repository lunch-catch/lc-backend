# 배치 작업 수동 재실행 API 설계

| 항목 | 내용 |
|---|---|
| 근거 | 이슈 #362. V52 요구사항명세서 102행 "실패로 확정된 작업만 수동으로 재실행할 수 있다" |
| 명세서 | [admin-api-spec.md](../../api-spec/admin-api-spec.md#배치-작업-수동-재실행) |
| 관련 문서 | [런치캐치_배치_운영.md](./런치캐치_배치_운영.md) 2장, 3장(수동 재실행 행) |

`런치캐치_배치_운영.md`의 "아직 비어 있는 것"에 있던 "관리자 수동 재실행"(화면에서 누르는 HTTP 경로)을 연다.
`BatchExecutionService.retry`는 이미 있었지만 상태만 되돌리고 실제로 작업을 다시 돌리지는 않았다. 이 PR이
그 둘을 잇는다.

## 서버가 하는 일

1. `jobName`이 00:00 묶음(`DAILY_0000`)이거나 등록된 `StandaloneJob`인지 본다
   (`BatchJobRegistry.classify(jobName).isRetryable()`, #585). 아니면 `422 OPS-001`이다.
2. (`jobName`, `businessDate`) 행을 읽는다. 없으면 `404 OPS-002`다.
3. 상태가 `FAILED`가 아니면 `409 OPS-003`이다.
4. `BatchExecutionService.retry`로 조건부 `UPDATE`를 건다. 0건이면(동시에 다른 요청이 먼저
   걸렸다) `409 OPS-003`이다.
5. 감사 로그를 요청 트랜잭션에 남긴다.
6. 재개를 별도 스레드에 접수시키고 `202`를 돌려준다. 실제 재개는 `BatchJobRunner.resume`이
   한다.

## 정한 이유

**결정: 재실행을 요청 안에서 끝낼지, 접수만 할지**

| 선택지 | 장점 | 단점 |
|---|---|---|
| A. 요청 스레드에서 끝까지 돌리고 결과를 응답에 담는다 | 결과를 바로 안다 | 00:00 묶음은 단계가 여러 개라 오래 걸릴 수 있어 관리자 화면이 그만큼 멈춘다 |
| B. 상태 전환과 감사 로그만 요청 안에서 하고, 실제 재개는 접수 뒤 별도 스레드에서 돈다 | 요청이 즉시 끝난다 | 결과를 바로 모른다. 감사 로그와 배치 실행 기록으로 확인해야 한다 |

B를 고른다. 분석의 수동 재계산(A-05, `AggregationRecalculationService`)이 같은 이유로 이미 이
방식을 쓰고 있어, 관리자가 배치를 다시 돌릴 때 기대하는 응답 모양이 이미 그렇게 자리 잡았다.

**결정: 재실행을 허용하는 jobName의 범위**

| 선택지 | 장점 | 단점 |
|---|---|---|
| A. `DAILY_0000` 묶음과 등록된 `StandaloneJob`만 받는다 | `DailyJobScheduler.takeOverStale`(이어받기)의 기존 분기와 경계가 같다. 상태만 되돌리고 실행을 보장하지 못하는 행이 생기지 않는다 | 개별 단계 하나만 다시 돌리고 싶을 때도 묶음 전체를 다시 불러야 한다 |
| B. FAILED 행이면 jobName 종류를 가리지 않고 받는다 | 제약이 없다 | 개별 단계 이름은 독립적으로 다시 실행할 경로가 없다. 상태만 RUNNING으로 바뀌고 아무도 닫지 않는 행이 영원히 남는다 |

A를 고른다. B는 이슈의 완료 조건("FAILED 행에 부르면 그 작업이 다시 실행되고 결과로 행이
닫힌다")을 개별 단계 이름에서 지킬 수 없다. 묶음을 다시 부르면 `begin`이 `SUCCESS` 단계를
건너뛰고 첫 `FAILED` 단계부터 이어가므로, "실패한 단계부터 다시 돈다"는 개별 단계를 따로
겨냥하지 않고도 B의 결과를 얻는다.

## 다시 쓴 기존 코드

`DailyJobScheduler.takeOverStale`(이어받기)과 이 API(수동 재실행)는 "묶음이면 `runBundle`, 등록된
`StandaloneJob`이면 `run`을 다시 부른다"는 같은 분기를 쓴다. 중복을 피하려고 그 분기를
`isRetryable`/`resume` 두 공개 메서드로 뽑았다.

그 둘을 어디에 둘지는 계층 규칙(설계 문서 1.5절, Service 는 Controller 와 Entry 에게만 불린다)이
정했다. `DailyJobScheduler`는 `job/`(Entry 계층)이라 Service 계층인 이 API의
`BatchExecutionRetryService`가 직접 부를 수 없다. 그래서 묶음을 실제로 도는 로직(`runBundle`,
`runStep`, `attempt`, `retryOrFail`)과 새 `isRetryable`/`resume`을 묶어 `ops.service.BatchJobRunner`
(Service 계층)로 옮겼다. `DailyJobScheduler`는 점유 확인(`claim`)과 스케줄 트리거만 남고
`BatchJobRunner`에 위임한다. 동작은 바뀌지 않았다. 기존 `DailyJobSchedulerTest`의 실행 로직
검증은 새 `BatchJobRunnerTest`로 옮기고, `DailyJobSchedulerTest`는 트리거가 `BatchJobRunner`에
올바르게 위임하는지만 보도록 줄였다. 둘 다 통과하는 것으로 확인했다.

#585 가 이름 분류(`isStep`/`isRetryable`)를 `ops.scheduler.BatchJobRegistry.classify` 로 떼어내고
실행 클래스 이름도 `BatchJobRunner` 로 바꿨다. 묶음만 돌리지 않고 묶음 밖 작업의 재개도 맡기 때문이다.

## 동시성

같은 (`jobName`, `businessDate`)로 두 요청이 거의 동시에 와도, 상태를 바꾸는 것은
`BatchExecutionService.retry`의 조건부 `UPDATE`(`WHERE status = 'FAILED'`) 하나뿐이다. 먼저 걸린
요청만 `RUNNING`으로 바뀌고 뒤에 온 요청은 0건을 받아 `409`로 거부된다. 읽고 나서 판단하는
구간이 없어 두 서버가 같은 상태를 함께 읽고 둘 다 자기 것이라 믿는 경쟁이 생기지 않는다.

## 트랜잭션

상태 조회와 조건부 `UPDATE`는 트랜잭션 밖에서 돈다. 감사 로그 기록만 별도 트랜잭션
(`TransactionTemplate`)에 담는다. 실제 재개(`BatchJobRunner.resume`)는 트랜잭션 밖, 별도
스레드에서 돈다. 감사 로그 기록이나 접수 자체(실행기 가득 참)가 실패하면 되돌린 행을 다시
`FAILED`로 닫는다. 그러지 않으면 아무도 재개하지 않는 `RUNNING` 행이 영원히 남는다.

## 성능

관리자가 누르는 수동 동작이라 트래픽이 거의 없다. 실행기는 1스레드, 대기열 4로 분석의 A-05와
같은 크기를 쓴다. 목표 TPS를 따로 두지 않는다.

## 다른 도메인에 공개한 기능

없다. `ops` 내부 기능이다.

## 아직 정해지지 않은 것

- 재개 결과(성공/실패)는 배치 실행 이력 조회(`GET /v1/admin/batch-executions`)로 확인한다.
  명세와 설계는 #570이 정했고([batch-execution-history-api-design.md](./batch-execution-history-api-design.md))
  구현은 #575가 했다.
- V52 요구사항명세서 102행의 정확한 기능 ID(FR 번호)를 확인하지 못했다. `api-spec.md`의
  "도메인과 문서" 표에 임시로 비워 두었다.
