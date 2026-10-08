# billing ERD

마이그레이션을 실제 MySQL 에 적용한 뒤 `information_schema` 에서 생성한 문서다.
**직접 고치지 않는다.** 스키마를 바꾼 뒤 `./gradlew generateErd` 로 다시 뽑는다.
`G-BUILD` 가 이 파일과 스키마가 어긋났는지 검사한다.

테이블 9개.

```mermaid
erDiagram
    budget_daily_plan {
        bigint budget_daily_plan_id PK
        bigint campaign_id "캠페인 ID. 소프트 참조(캠페인 도메인)"
        bigint owner_id "점주 ID. 소프트 참조. 00:00 예약 때 캠페인 조회로 받아 둔다. 노출 차감의 원장 owner_id"
        date business_date
        bigint daily_budget
        bigint unit_price
        datetime(6) created_at
        datetime(6) updated_at
    }
    budget_hourly_target {
        bigint budget_hourly_target_id PK
        bigint budget_daily_plan_id "같은 도메인 테이블(budget_daily_plan) 참조. 실제 FK"
        tinyint hour
        bigint target_amount
        datetime(6) created_at
        datetime(6) updated_at
    }
    deduction_result {
        bigint deduction_result_id PK
        binary(16) serve_id UK
        bigint campaign_id
        bigint owner_id
        date business_date "노출 영업일"
        varchar(20) billing_status "BILLED/NOT_BILLED_BUDGET/NOT_BILLED_PAUSED"
        bigint unit_price "그날 단가(budget_daily_plan.unit_price). BILLED면 DEDUCT 금액"
        datetime(6) created_at
        datetime(6) updated_at
    }
    payment {
        bigint payment_id PK
        bigint owner_id "소프트 참조"
        varchar(64) order_id UK "[2026-10-01 신규] 가맹점(우리 서버) 발급 주문번호. PG 결제창을 열기 전에 이 행을 먼저 만들어두고, 승인 확정 시 이 값 기준으로 금액 위변조를 검증한다"
        varchar(100) pg_tid UK "[2026-10-01] PG사 결제 식별자(토스페이먼츠 paymentKey 등). 주문 생성 시점엔 NULL, 승인 시도(confirm) 시점에 채워짐"
        bigint amount
        varchar(20) status
        varchar(255) failure_reason "[2026-10-01 신규] PG 실패 사유. FAILED일 때만 값 존재"
        varchar(100) pg_cancel_id "[2026-10-01 신규] PG 취소 식별자. 승인 후 PG측에서 취소된 경우에만 값 존재(승인 전에 취소된 주문은 NULL)"
        datetime(6) approved_at "nullable"
        datetime(6) canceled_at "[2026-10-01 신규] 취소 확정 시각. CANCELLED일 때만 값 존재"
        datetime(6) created_at
        datetime(6) updated_at
    }
    payment_reconciliation_outbox {
        bigint payment_reconciliation_outbox_id PK
        bigint payment_id UK
        varchar(20) status
        int attempt_count
        int max_retry
        datetime(6) next_retry_at "nullable"
        datetime(6) last_checked_at "nullable"
        varchar(500) last_error "nullable"
        datetime(6) created_at
        datetime(6) updated_at
    }
    point_ledger {
        bigint point_ledger_id PK
        bigint owner_id "소프트 참조. 잔액은 이 테이블 SUM으로 직접 계산(별도 잔액 컬럼/캐시 없음, DEDUCT 제외)"
        bigint campaign_id "소프트 참조. DEDUCT/REFUND/RESERVE/RELEASE의 캠페인별 breakdown용"
        binary(16) serve_id "[2026-10-01 변경, BIGINT -> BINARY(16)] 소프트 참조. DEDUCT만 값 존재. 발급 주체(광고서빙)가 UUID로 구현해 타입을 맞춤"
        bigint payment_id "CHARGE는 항상 값 존재. RESERVE도 값을 가질 수 있음(충전 직후 재예약 트리거). 정기(00:00) RESERVE는 NULL"
        bigint refund_request_id "REFUND만 값 존재"
        date business_date "RESERVE/RELEASE만 값 존재. RESERVE/RELEASE 대상 영업일자"
        varchar(20) entry_type "CHARGE/RESERVE/DEDUCT/RELEASE/REFUND/ADJUST. 매일 00:00 1회 RESERVE(잔액에서 실차감) -> 노출마다 DEDUCT(그날 예약액 한도 내 실시간 차감, 잔액엔 미반영) -> 13:30 미소진분 RELEASE(잔액 복원. 00:00에는 전날 해제가 실패해 남은 예약만 정리)"
        varchar(20) source
        bigint amount "변동량. 부호는 entry_type에 종속"
        varchar(255) reason "ADJUST 사유(필수)"
        varchar(64) idempotency_key "[13차 확정] ADJUST 전용 멱등 키(P-19 Idempotency-Key 헤더). 같은 점주와 같은 키의 조정은 1건만 기록"
        datetime(6) created_at
        datetime(6) updated_at
        varchar(50) day_reserve_dedup_key UK "nullable"
    }
    point_policy {
        bigint point_policy_id PK
        bigint min_charge_amount
        json charge_products "[2026-10-01 신규] 허용 충전 금액 목록(예: [10000,30000,50000,100000]). P-01 1단계 검증과 P-04 응답에 씀"
        varchar(255) change_reason "[2026-10-01 신규] 이 정책으로 변경한 사유(선택)"
        date effective_date UK
        bigint created_by "admin_id, 소프트 참조"
        datetime(6) created_at
        datetime(6) updated_at
    }
    refund_request {
        bigint refund_request_id PK
        bigint owner_id "소프트 참조"
        bigint payment_id "nullable"
        bigint amount "항상 요청 시점 미소진 잔액 전액"
        bigint fee "환불 정책상 수수료"
        varchar(20) status
        varchar(255) reason "[2026-10-01 완화] 요청 사유. 명세상 선택이라 NOT NULL 해제"
        varchar(255) reject_reason "[2026-10-01 신규] 반려 사유. REJECTED일 때 필수. 감사 로그 전용이 아니라 점주에게 직접 보여주는 값이라 컬럼으로 둠"
        datetime(6) processed_at "nullable"
        bigint processed_by "admin_id, 소프트 참조"
        datetime(6) created_at
        datetime(6) updated_at
        bigint pending_dedup_key UK "nullable"
    }
    settlement_mismatch {
        bigint settlement_mismatch_id PK
        varchar(30) check_type
        bigint target_id "소프트 참조"
        bigint expected_value
        bigint actual_value
        varchar(30) resolution_type "nullable"
        bigint job_execution_id "BatchExecutionLog(운영 도메인) 소프트 참조. Spring Batch 메타테이블 아님"
        datetime(6) resolved_at "nullable"
        bigint resolved_by "admin_id, 소프트 참조"
        datetime(6) created_at
        datetime(6) updated_at
    }
    budget_daily_plan ||--o{ budget_hourly_target : "budget_daily_plan_id"
    payment ||--o{ payment_reconciliation_outbox : "payment_id"
    payment ||--o{ point_ledger : "payment_id"
    refund_request ||--o{ point_ledger : "refund_request_id"
    payment ||--o{ refund_request : "payment_id"
```
