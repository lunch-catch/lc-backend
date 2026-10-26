# billing API 설계

[billing API 명세](../../api-spec/billing-api-spec.md)의 응답을 서버가 어떻게 만드는지와 그렇게 정한 이유를 적는다. 노출 차감부터 대사까지의 구조는 [구조와 동작 설계](./billing_구조와_동작_설계.md)가, 배치와 내부 흐름은 [정산 배치 설계](./billing-batch-design.md)가 갖는다.

## 결제 주문과 승인 확정

서비스들이 함께 사용하는 제약 이름과 기본 조회 실패 문구의 소유 위치는 [구조와 동작 설계의 결제 상수 절](./billing_구조와_동작_설계.md#결제-서비스의-공통-상수)을 따른다.

### `POST /v1/owner/payments`

`PaymentOrderService`가 한 트랜잭션에서 확인 중인 `UNKNOWN` 결제 유무와 현재 충전 정책을 확인한다. `REQUESTED` 주문만 있는 점주는 새 주문을 만들 수 있다. 정책은 서버 시계의 오늘 이하 적용일 중 가장 늦은 행이다.

검증한 금액을 `REQUESTED` 결제에 저장한다. 주문 번호는 `ord_`와 KST 날짜, UUID로 만들고 결제 식별자 `pg_tid`는 비워 둔다. 승인 확정은 이 저장 금액만 사용하므로 클라이언트가 승인 요청에서 금액을 바꿀 수 없다.

### `POST /v1/owner/payments/{paymentId}:confirm`

`PaymentConfirmationService`는 PG를 기다리는 동안 DB 트랜잭션을 유지하지 않는다. 처리 경계는 다음과 같다.

1. 트랜잭션 밖에서 결제 소유자, 주문 번호와 현재 상태를 확인한다. `REQUESTED`만 PG 승인으로 진행한다. 다른 상태의 응답은 명세를 따른다.
2. `PaymentResultService.assignPgTid`가 짧은 트랜잭션에서 결제 행을 잠근다. 식별자가 이미 있으면 같은 값만 허용한다. 다른 결제에 같은 식별자가 있는 경우도 `BILLING-017`로 거절한다.
3. 식별자를 먼저 커밋한 뒤 PG에 저장된 주문 번호와 금액으로 승인을 요청한다. `TossPaymentGateway`는 `paymentKey`를 `Idempotency-Key`로 보낸다. 이 헤더의 전송만으로 PG의 멱등 지원을 입증한 것은 아니다.
4. 결과를 아래와 같이 분류하고 `PaymentResultService`의 별도 트랜잭션으로 기록한다.

| PG 결과 | 서버 처리 |
|---|---|
| 승인 응답의 주문 번호와 금액이 일치함 | `APPROVED`와 `CHARGE`를 함께 기록한다 |
| 명확한 거절 | 실패 사유와 `FAILED`를 기록한다 |
| 미전송이 확실함(`PgNotSentException`) | `REQUESTED`를 유지한다. 재조회 아웃박스를 만들지 않는다 |
| 결과 불명(`PgUnknownException`) | 저장한 결제 키로 동기 재조회를 한 번 한다 |
| 승인 응답의 주문 번호나 금액이 다름 | 승인이나 실패로 단정하지 않고 `UNKNOWN`과 아웃박스를 기록한다 |

동기 재조회에서 `APPROVED`는 주문 번호와 금액이 일치해야 승인한다. `FAILED`는 실패로, `EXPIRED`와 `CANCELED`는 충전 없이 취소로 닫는다. `PENDING`, `NOT_FOUND`, 조회 실패와 승인 정보 불일치는 `UNKNOWN`으로 남긴다. `ALREADY_PROCESSED_PAYMENT`도 게이트웨이가 결과 불명으로 분류하므로 동기 재조회가 결과를 정한다.

승인·조회에서 결과를 확정할 수 없는 HTTP 오류는 `TOSS_CONFIRM_UNKNOWN`·`TOSS_INQUIRE_UNKNOWN` 로그에 HTTP 상태, 토스 오류 코드(`code`), 오류 메시지(`pgMessage`), 응답 Content-Type(`contentType`), 수신 본문 바이트 수(`responseBodyBytes`)를 남긴다. 본문이 없는 HTTP 500과 JSON이 아닌 오류 응답을 구분하기 위한 값이다. 외부 문자열은 비밀키 원문과 인코딩을 마스킹하고 제어 문자를 공백으로 바꾼 뒤 512자로 제한한다. 요청 헤더와 응답 본문 전체는 남기지 않는다. 본문이 비어 있으면 세부 오류 사유는 이 로그만으로 알 수 없다.

`PaymentResultService`는 결과를 쓸 때마다 결제 행을 잠그고 상태를 다시 읽는다. 이미 `APPROVED`, `FAILED`, `CANCELLED`이면 덮어쓰지 않는다. 승인 확정·웹훅·재조회 워커·관리자 재조회가 같은 기록 메서드를 사용한다.

승인 트랜잭션은 점주 계정 행이 없으면 만들고 `CHARGE`를 기록한다. 아웃박스가 있으면 `RESOLVED`로 닫는다. 새 충전에만 내부 통보 `PointCharged`를 발행하며 재예약 리스너는 커밋 뒤 실행한다. `UNKNOWN` 전환과 `PENDING` 아웃박스 생성도 한 트랜잭션이다.

PG 승인 시도 전에 식별자를 커밋하는 이유는 호출 중 서버가 종료돼도 조회할 근거를 남기기 위해서다. 다만 `REQUESTED`에서 서버가 종료되면 아웃박스는 아직 없을 수 있다. [PG 대사](./billing-batch-design.md#i-05-정산-대사-배치-2종)는 이 결제를 조회하지만 두 쪽의 승인 상태가 다르면 불일치만 기록한다. 모든 결제를 자동 승인으로 복구한다는 보장은 없다.

## `POST /webhook/payments`

승인의 주 경로는 웹훅이 아니라 프론트가 부르는 승인 확정(`:confirm`)이다. 웹훅은 그 경로가 놓친 결제의 상태를 맞추는 보조 경로다. 점주가 승인 전에 창을 닫아 만료됐거나, 서버가 승인 결과를 받지 못한 건이 여기에 해당한다.

PG 서버가 호출한다. `/v1/` 접두사가 없는 것은 서버 대 서버 콜백이고 인증이 이 서비스의 JWT가 아니라 PG 방식이기 때문이다. 카카오 연결 해제 웹훅과 같은 이유다.

본문은 `eventType`(`PAYMENT_STATUS_CHANGED`), `createdAt`, 상태가 바뀐 Payment 객체인 `data`다. 서버는 `data.paymentKey`와 `data.orderId`만 꺼낸다. 확인한 Toss 문서에서 서명 헤더는 정산·셀러 웹훅에만 나오므로 **결제 상태 웹훅은 서명으로 검증할 수 없고, 본문의 상태값을 믿지 않는다.** 결제 식별자로 PG에 다시 물어 상태를 확정한다.

**응답 규칙.**

- 처리할 수 없는 요청(모르는 `orderId`, 금액 불일치, 형식 오류)은 다시 보내도 소용없으므로 로그만 남기고 `200`을 돌려준다.
- PG 조회가 일시적으로 실패했거나 서버 오류가 난 경우에는 `5xx`로 응답해 PG의 재전송에 맡긴다.
- 확인한 Toss 가이드 기준으로 PG는 10초 안에 `200`을 받아야 하고, 아니면 최대 7회 재전송한다(1·4·16·64·256·1024·4096분 간격). 그래서 PG 조회 호출에는 짧은 타임아웃을 둔다.
- 같은 `orderId`의 PG 조회가 10초 안에 반복되면 PG를 부르지 않고 `429`를 돌려준다(`WebhookInquiryGuard`). 정상 재전송은 최소 1분 간격이라 걸리지 않는다.
- 이미 확정된 결제는 PG를 조회하지 않고 `200`이다.

서버는 알려진 주문의 미확정 결제에 대해서만 PG 조회를 한다. 조회한 주문 번호와 금액이 저장값과 같아야 결과를 적용한다. 요청 본문의 상태는 판정에 사용하지 않는다.

- 승인 결과는 위 결제 결과 기록을 공유한다. `REQUESTED`라면 웹훅의 결제 식별자를 먼저 기록하며 기존 식별자와 충돌하면 무시한다.
- `FAILED`는 식별자가 있는 결제에만 적용한다. 식별자가 없는 주문을 실패로 바꾸면 DB의 상태별 식별자 CHECK에 어긋난다.
- `EXPIRED`와 `CANCELED`는 미확정 결제를 충전 없이 취소로 닫는다. `PENDING`과 `NOT_FOUND`는 무시한다.
- 웹훅은 새 `UNKNOWN`이나 아웃박스를 만들지 않는다. 이미 승인된 결제의 PG 취소는 대사와 관리자 정정의 대상이다.

## 환불 요청과 관리자 처리

### `POST /v1/owner/refund-requests`

`OwnerRefundService.request`는 점주 계정 행 잠금을 트랜잭션의 첫 조회로 수행한다. 계정 행이 없으면 환불 가능 잔액이 없는 것으로 거절한다. 잠근 뒤 잔액, `CampaignQueryService.hasLiveCampaign`, 대기 요청 순서로 검사한다. 잔액 전액으로 `REQUESTED` 요청을 만들며 원장은 바꾸지 않는다.

대기 요청은 애플리케이션 검사와 `uk_refund_request_pending`이 함께 막는다. 이 제약 위반만 대기 요청 중복 오류로 바꾸고 다른 무결성 오류는 전파한다. 미리보기는 잠그지 않는 읽기 전용 조회이며 같은 순서로 첫 차단 사유를 정한다.

### 관리자 환불 승인과 반려

승인은 다음 경계로 처리한다.

1. `AdminRefundProcessService`가 트랜잭션 밖에서 요청의 점주 ID를 읽는다.
2. `RefundApprovalRecorder`가 트랜잭션을 시작하고 점주 계정 행, 환불 요청 행 순서로 잠근다.
3. 요청이 대기 중인지, 집행 중 캠페인이 없는지, 현재 잔액이 요청 금액 이상인지 다시 확인한다.
4. `REFUND` 원장, 요청의 `COMPLETED` 전환과 감사 로그 `REFUND_APPROVE`를 함께 커밋한다.

반려는 원장을 바꾸지 않아 환불 요청 행만 잠근다. 대기 중 요청을 `REJECTED`로 바꾸고 감사 로그 `REFUND_REJECT`를 같은 트랜잭션에 남긴다. 승인과 반려가 겹치면 요청 행을 나중에 얻은 쪽이 처리 완료 상태를 보고 거절한다.

실제 PG 취소나 송금은 호출하지 않는다. mock 환불이므로 승인과 지급을 나누는 중간 `APPROVED` 상태도 사용하지 않는다. 결제별 취소와 여러 결제에서 생긴 잔액의 전액 환불은 단위가 달라 `refund_request.payment_id`는 비워 둔다.

### 잠금 대기와 읽기 순서

계정 잠금 뒤에 잔액을 읽어야 MySQL 기본 격리 수준의 스냅샷이 잠금 대기 전에 고정되지 않는다. 먼저 일반 조회를 하면 그사이 커밋된 예약·해제·조정을 놓칠 수 있다.

`PointAccountRepository.lockByOwnerId`는 `SET_VAR(innodb_lock_wait_timeout=5)` 힌트를 보낸다. DB가 힌트를 수용하지 않으면 기본 대기 상한을 사용하므로 응답을 반드시 5초 안에 반환한다는 보장은 없다. 환불 요청·승인과 포인트 조정은 잠금 실패 예외를 `503 BILLING-006`으로 바꾼다.

## 관리자 포인트 원장 조회

`AdminPointLedgerService`는 한 읽기 전용 트랜잭션에서 커서 페이지와 잔액 계산의 기준을 읽는다. 정렬 키는 `(created_at, point_ledger_id)`다. 요청 크기보다 한 건 더 읽고 다음 페이지가 있으면 반환할 마지막 행으로 토큰을 만든다.

변동 전·후 잔액은 저장하지 않는다. 기간 시작 전의 잔액에서 출발해 기간 안의 잔액 변동을 기록 시각·ID 순서로 누적한다. 종류나 캠페인 필터는 페이지에만 적용하고 잔액 누적에서는 제외한다. `DEDUCT`는 예약 때 이미 잔액에서 빠진 금액이라 잔액을 다시 바꾸지 않는다. 따라서 기간 전체의 `DEDUCT`를 잔액 계산용으로 훑지 않는다.

## `GET /v1/admin/point-ledger/summary`

점주 한 명의 기간별 합계를 원장에서 바로 계산한다.

- **날짜 귀속:** `RESERVE`·`RELEASE`는 `business_date`로, `CHARGE`·`REFUND`는 `created_at`을 서버가 KST 날짜로 바꿔서, `ADJUST`는 `adjust_target_date`(없으면 `created_at`의 KST 날짜)로 구간에 넣는다. DB 날짜 함수는 쓰지 않는다(요구사항 명세서 비기능 시트 42행).
- **`DEDUCT`는 읽지 않는다.** 노출당 1행이라 기간 합계를 위해 훑을 수 없다. 영업일별로 `RELEASE = RESERVE − DEDUCT`(요구사항 명세서 `비즈니스 규칙` 시트 16행)이므로 지난 영업일의 `|DEDUCT|`는 −(`RESERVE` 합 + `RELEASE` 합)이다. 오늘 영업일은 다음 날 00:00 해제 전이라, 그날 `RELEASE`가 아직 없으면 그날의 `DEDUCT` 합을 직접 읽는다(하루치뿐이다).
- 읽는 행은 기간 안의 `CHARGE`·`REFUND`, 정정 대상 영업일이 기간 안인 `ADJUST`(대상이 없는 `ADJUST`는 기록 시각이 기간 안인 것), 기간 영업일의 `RESERVE`·`RELEASE`, 오늘의 `DEDUCT`뿐이다. V2 마이그레이션의 `idx_point_ledger_owner_type_created`와 `idx_point_ledger_owner_business_date`를 쓴다.

## 관리자 포인트 정책

`AdminPointPolicyService`는 서버 시계의 오늘 이하 최신 정책과 내일 적용분을 나눠 읽는다. 이력은 적용일 내림차순이다. 저장은 항상 서버가 정한 내일 날짜를 대상으로 한다.

`PointPolicy` 팩터리로 입력을 검증한 뒤 `PointPolicyRepository.upsert`가 적용일 UNIQUE를 기준으로 저장한다. 내일 행을 삭제하고 다시 넣으면 동시 최초 저장이 UNIQUE 위반으로 실패할 수 있어 upsert를 사용한다. 같은 트랜잭션에서 `POINT_POLICY_SAVE` 감사 로그를 쓴다.

변경 전 값은 잠그지 않고 읽는다. 없는 내일 행을 먼저 잠그면 동시 저장이 간격 잠금으로 교착할 수 있다. 대신 두 관리자가 동시에 저장하면 한 감사 로그의 변경 전 값이 다른 저장을 건너뛸 수 있다. 저장 전·후 값을 직렬 순서대로 감사한다는 보장은 없다.

## 포인트 조정

`PointAdjustmentService`는 트랜잭션 밖에서 입력과 `OwnerStatusQuery.findStatus`의 존재 여부를 검사한다. 점주 상태 자체로 제한하지 않으므로 정지·탈퇴 점주의 과오 차감도 정정할 수 있다. `campaignId`와 `targetBusinessDate`는 함께 있어야 하며 미래 영업일은 받지 않는다.

`PointAdjustmentRecorder`가 수행하는 순서는 다음과 같다.

1. 점주 계정 행을 먼저 잠근다. 행이 없으면 생성한다. 양수 조정도 같은 잠금을 사용한다.
2. 같은 `(owner_id, idempotency_key)`를 찾는다. 금액·캠페인·정정 대상 영업일·사유가 같으면 기존 결과를 돌려준다. `mismatchId`는 원장에 저장하지 않아 재요청 비교에서 제외한다.
3. 새 요청에 `mismatchId`가 있으면 미해결 여부와 점주 소유를 확인한다. 사람이 돈을 움직이는 경로라 잘못 닫힌 대사 건을 되돌릴 방법이 없기 때문이다. 캠페인 대상 대사 건은 캠페인 ID와 영업일도 요청과 같아야 한다. 결제 대상은 `payment`를 잠그지 않고 읽는다.
4. 음수 조정은 잔액이 음수가 되지 않는지 확인한다. `ADJUST`를 `MANUAL`로 기록한다.
5. 대사 건을 닫는 경우 `resolution_type IS NULL` 조건으로 갱신한다. 영향 행이 없으면 원장까지 롤백한다. `POINT_ADJUST` 감사 로그도 같은 트랜잭션에 쓴다.

잠금·기록 순서는 `point_account`, `point_ledger`, `settlement_mismatch`다. 충전 승인이 `payment` 뒤 `point_account`를 잡으므로 조정에서는 `payment`를 잠그지 않는다. 양수도 잠가 같은 키 재요청과 대사 건 닫기, 감사 로그를 한 트랜잭션으로 유지한다.

양수 조정은 과오 차감 정정으로 취급한다. 캠페인 정정은 원래 영업일에 귀속하며 예약액과 진행률 계산에는 섞지 않는다. 분석이 읽는 집계 계약과 재집계 대상 날짜는 [구조와 동작 설계](./billing_구조와_동작_설계.md#정정-대상-영업일)가 갖는다.

## `GET /v1/admin/settlement-mismatches`

같은 `(checkType, targetId, businessDate)`를 다시 탐지하면 새 행을 만들지 않고 기존 행을 갱신한다. `UNIQUE(check_type, target_id, business_date)` 때문에 upsert로 구현한다. `businessDate`와 이 UNIQUE는 `V2__billing_pause_mismatch_date_and_indexes.sql`로 들어갔다.

`SettlementMismatchQueryService`는 대사 대상 영업일과 유형·해결 여부로 조회한다. 정렬은 `createdAt` 또는 `businessDate`에 ID를 보조 키로 붙인다. 전체 건수를 제공하는 페이지 번호 방식을 유지한다. 관리자 표 목록이고, 전체 건수(`totalElements`)를 매출 현황의 불일치 건수로 쓰기 때문이다. 정책 이력도 관리자 표 목록이라 페이지 번호 방식이다. 실제 체크 유형은 V5가 추가한 `CAMPAIGN_OVERSPEND`까지 세 종류다.

## 오류 코드 번호

명세의 `BILLING-030`·`031`·`032`를 `016`·`017`·`018`로 당긴 것은 저장소의 `ErrorCodeCatalogTest`가 접두어마다 번호가 1부터 끊기지 않기를 요구하기 때문이다. 016~029를 비워 둘 수 없다.

## 구현 확인 근거

| 흐름 | 확인한 구현 |
|---|---|
| 주문·승인·상태 기록 | `PaymentConfirmationService` |
| | `PaymentOrderService` |
| | `PaymentResultService` |
| | `TossPaymentGateway` |
| 웹훅 | `PaymentWebhookController` |
| | `PaymentWebhookService` |
| | `WebhookInquiryGuard` |
| 환불 | `AdminRefundProcessService` |
| | `OwnerRefundService` |
| | `RefundApprovalRecorder` |
| | `RefundRequestRepository` |
| 원장·정책·조정 | `AdminPointLedgerService` |
| | `AdminPointPolicyService` |
| | `AdminPointSummaryService` |
| | `PointAdjustmentRecorder` |
| | `PointAdjustmentService` |
| 저장 제약 | billing의 V1~V6 마이그레이션 |
| | `PointAccountRepository` |
| | `PointLedgerRepository` |
| | `PointPolicyRepository` |

처리 순서와 저장 제약은 코드로 대조했다. 점주 API의 ACTIVE 검사는 별도 PR #488의 범위다.
