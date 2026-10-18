# billing API 설계

[billing API 명세](../../api-spec/billing-api-spec.md)의 응답을 서버가 어떻게 만드는지와 그렇게 정한 이유를 적는다. 노출 차감부터 대사까지의 구조는 [구조와 동작 설계](./billing_구조와_동작_설계.md)가, 배치와 내부 흐름은 [정산 배치 설계](./billing-batch-design.md)가 갖는다.

## `POST /webhook/payments`

승인의 주 경로는 웹훅이 아니라 프론트가 부르는 승인 확정(`:confirm`)이다. 웹훅은 그 경로가 놓친 결제의 상태를 맞추는 보조 경로다. 점주가 승인 전에 창을 닫아 만료됐거나, 서버가 승인 결과를 받지 못한 건이 여기에 해당한다.

PG 서버가 호출한다. `/v1/` 접두사가 없는 것은 서버 대 서버 콜백이고 인증이 이 서비스의 JWT가 아니라 PG 방식이기 때문이다. 카카오 연결 해제 웹훅과 같은 이유다.

본문은 `eventType`(`PAYMENT_STATUS_CHANGED`), `createdAt`, 상태가 바뀐 Payment 객체인 `data`다. 서버는 `data.paymentKey`와 `data.orderId`만 꺼낸다. 확인한 Toss 문서에서 서명 헤더는 정산·셀러 웹훅에만 나오므로 **결제 상태 웹훅은 서명으로 검증할 수 없고, 본문의 상태값을 믿지 않는다.** 결제 식별자로 PG에 다시 물어 상태를 확정한다.

**응답 규칙.**

- 처리할 수 없는 요청(모르는 `orderId`, 금액 불일치, 형식 오류)은 다시 보내도 소용없으므로 로그만 남기고 `200`을 돌려준다.
- PG 조회가 일시적으로 실패했거나 서버 오류가 난 경우에는 `5xx`로 응답해 PG의 재전송에 맡긴다.
- 확인한 Toss 가이드 기준으로 PG는 10초 안에 `200`을 받아야 하고, 아니면 최대 7회 재전송한다(1·4·16·64·256·1024·4096분 간격). 그래서 PG 조회 호출에는 짧은 타임아웃을 둔다.
- **2026-10-14:** 같은 `orderId`의 PG 조회가 10초 안에 반복되면 PG를 부르지 않고 `429`를 돌려준다(`WebhookInquiryGuard`). 정상 재전송은 최소 1분 간격이라 걸리지 않는다.
- 이미 확정된 결제는 PG를 조회하지 않고 `200`이다.

## `GET /v1/admin/point-ledger/summary`

점주 한 명의 기간별 합계를 원장에서 바로 계산한다.

- **날짜 귀속:** `RESERVE`·`RELEASE`는 `business_date`로, `CHARGE`·`REFUND`는 `created_at`을 서버가 KST 날짜로 바꿔서, `ADJUST`는 `adjust_target_date`(없으면 `created_at`의 KST 날짜)로 구간에 넣는다. DB 날짜 함수는 쓰지 않는다(요구사항 명세서 비기능 시트 42행).
- **`DEDUCT`는 읽지 않는다.** 노출당 1행이라 기간 합계를 위해 훑을 수 없다. 영업일별로 `RELEASE = RESERVE − DEDUCT`(요구사항 명세서 `비즈니스 규칙` 시트 16행)이므로 지난 영업일의 `|DEDUCT|`는 −(`RESERVE` 합 + `RELEASE` 합)이다. 오늘 영업일은 13:30 해제 전일 수 있어, 그날 `RELEASE`가 아직 없으면 그날의 `DEDUCT` 합을 직접 읽는다(하루치뿐이다).
- 읽는 행은 기간 안의 `CHARGE`·`REFUND`, 정정 대상 영업일이 기간 안인 `ADJUST`(대상이 없는 `ADJUST`는 기록 시각이 기간 안인 것), 기간 영업일의 `RESERVE`·`RELEASE`, 오늘의 `DEDUCT`뿐이다. V2 마이그레이션의 `idx_point_ledger_owner_type_created`와 `idx_point_ledger_owner_business_date`를 쓴다.

## `GET /v1/admin/settlement-mismatches`

같은 `(checkType, targetId, businessDate)`를 다시 탐지하면 새 행을 만들지 않고 기존 행을 갱신한다. `UNIQUE(check_type, target_id, business_date)` 때문에 upsert로 구현한다. `businessDate`와 이 UNIQUE는 `V2__billing_pause_mismatch_date_and_indexes.sql`로 들어갔다.

## 오류 코드 번호

명세의 `BILLING-030`·`031`·`032`를 `016`·`017`·`018`로 당긴 것은 저장소의 `ErrorCodeCatalogTest`가 접두어마다 번호가 1부터 끊기지 않기를 요구하기 때문이다. 016~029를 비워 둘 수 없다.
