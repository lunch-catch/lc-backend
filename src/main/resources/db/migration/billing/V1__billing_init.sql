-- billing 도메인 초기 스키마. 드라이브 ERD 폴더 전체_flyway.sql 의 6장과 같다
-- 공통 규칙(소프트 참조, CHECK, 시각, created_at/updated_at)은 같은 폴더의 README.md 를 따른다
-- 대상: MySQL 8.4, InnoDB, utf8mb4_0900_ai_ci

-- ############################################################################
-- 6. 정산 (billing)    담당 김현우
-- ############################################################################

-- ----------------------------------------------------------------------------
-- point_policy  |  포인트 정책 이력형 테이블(충전 상품, 최소 충전 금액). [2026-10-05] 운영 설정값과 겹치는 4개 칼럼 제거
--   소프트 참조: created_by -> admin(관리자)
-- ----------------------------------------------------------------------------
CREATE TABLE point_policy (
    point_policy_id           BIGINT        NOT NULL AUTO_INCREMENT,
    min_charge_amount         BIGINT        NOT NULL,
    charge_products           JSON          NOT NULL                 COMMENT '[2026-10-01 신규] 허용 충전 금액 목록(예: [10000,30000,50000,100000]). P-01 1단계 검증과 P-04 응답에 씀',
    change_reason             VARCHAR(255)                           COMMENT '[2026-10-01 신규] 이 정책으로 변경한 사유(선택)',
    effective_date            DATE          NOT NULL,
    created_by                BIGINT        NOT NULL                 COMMENT 'admin_id, 소프트 참조',
    created_at                DATETIME(6)   NOT NULL,
    updated_at                DATETIME(6)   NOT NULL,

    PRIMARY KEY (point_policy_id),
    UNIQUE KEY uk_point_policy_effective_date (effective_date),

    CONSTRAINT chk_point_policy_charge_products CHECK ((json_type(charge_products) = 'ARRAY') AND (json_length(charge_products) > 0)),
    CONSTRAINT chk_point_policy_min_charge CHECK (min_charge_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='포인트 정책 이력형 테이블(21행: 충전 상품, 최소 충전 금액). [2026-10-05] 노출 단가, 최소와 부트스트랩 하루 예산, 추천 하루 예산 제거. 앞의 셋은 platform_setting(27행)';

-- ----------------------------------------------------------------------------
-- payment  |  결제(충전). order_id를 PG 결제창 열기 전에 먼저 생성하고, APPROVED 확정 시에만 point_ledger에 CHARGE 기록. [2026-10-01] order_id/failure_reason/pg_cancel_id/canceled_at 추가, approved_at CHECK 완화
--   소프트 참조: owner_id -> owner(점주)
-- ----------------------------------------------------------------------------
CREATE TABLE payment (
    payment_id    BIGINT        NOT NULL AUTO_INCREMENT,
    owner_id      BIGINT        NOT NULL                               COMMENT '소프트 참조',
    order_id      VARCHAR(64)   NOT NULL                               COMMENT '[2026-10-01 신규] 가맹점(우리 서버) 발급 주문번호. PG 결제창을 열기 전에 이 행을 먼저 만들어두고, 승인 확정 시 이 값 기준으로 금액 위변조를 검증한다',
    pg_tid        VARCHAR(100)                                         COMMENT '[2026-10-01] PG사 결제 식별자(토스페이먼츠 paymentKey 등). 주문 생성 시점엔 NULL, 승인 시도(confirm) 시점에 채워짐',
    amount        BIGINT        NOT NULL,
    status        VARCHAR(20)   NOT NULL DEFAULT 'REQUESTED',
    failure_reason   VARCHAR(255)                                         COMMENT '[2026-10-01 신규] PG 실패 사유. FAILED일 때만 값 존재',
    pg_cancel_id  VARCHAR(100)                                         COMMENT '[2026-10-01 신규] PG 취소 식별자. 승인 후 PG측에서 취소된 경우에만 값 존재(승인 전에 취소된 주문은 NULL)',
    approved_at   DATETIME(6),
    canceled_at   DATETIME(6)                                          COMMENT '[2026-10-01 신규] 취소 확정 시각. CANCELLED일 때만 값 존재',
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,

    PRIMARY KEY (payment_id),
    UNIQUE KEY uk_payment_order_id (order_id),
    UNIQUE KEY uk_payment_pg_tid (pg_tid),

    CONSTRAINT chk_payment_amount CHECK (amount > 0),
    CONSTRAINT chk_payment_approved_at CHECK ((status = 'APPROVED' AND approved_at IS NOT NULL) OR status = 'CANCELLED' OR (status IN ('REQUESTED', 'FAILED', 'UNKNOWN') AND approved_at IS NULL)),
    CONSTRAINT chk_payment_canceled_at CHECK ((status = 'CANCELLED' AND canceled_at IS NOT NULL) OR (status <> 'CANCELLED' AND canceled_at IS NULL)),
    CONSTRAINT chk_payment_failure_reason CHECK ((status = 'FAILED' AND failure_reason IS NOT NULL) OR (status <> 'FAILED' AND failure_reason IS NULL)),
    CONSTRAINT chk_payment_pg_cancel_id CHECK (pg_cancel_id IS NULL OR status = 'CANCELLED'),
    CONSTRAINT chk_payment_pg_tid CHECK ((status IN ('APPROVED', 'FAILED', 'UNKNOWN') AND pg_tid IS NOT NULL) OR status IN ('REQUESTED', 'CANCELLED')),
    CONSTRAINT chk_payment_status CHECK (status IN ('REQUESTED', 'APPROVED', 'FAILED', 'CANCELLED', 'UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='결제(충전). order_id를 PG 결제창 열기 전에 먼저 생성하고, APPROVED 확정 시에만 point_ledger에 CHARGE 기록. [2026-10-01] order_id/failure_reason/pg_cancel_id/canceled_at 추가, approved_at CHECK 완화';

-- ----------------------------------------------------------------------------
-- payment_reconciliation_outbox  |  외부 PG 상태 미확정(UNKNOWN) 건 재시도용. payment 테이블은 정산.sql 참고
-- ----------------------------------------------------------------------------
CREATE TABLE payment_reconciliation_outbox (
    payment_reconciliation_outbox_id  BIGINT        NOT NULL AUTO_INCREMENT,
    payment_id                        BIGINT        NOT NULL,
    status                            VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempt_count                       INT           NOT NULL DEFAULT 0,
    max_retry                         INT           NOT NULL,
    next_retry_at                     DATETIME(6),
    last_checked_at                   DATETIME(6),
    last_error                        VARCHAR(500),
    created_at                        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                        DATETIME(6)   NOT NULL,

    PRIMARY KEY (payment_reconciliation_outbox_id),
    UNIQUE KEY uk_payment_reconciliation_outbox_payment (payment_id),
    KEY idx_payment_reconciliation_outbox_retry (status, next_retry_at),

    CONSTRAINT fk_payment_reconciliation_outbox_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),

    CONSTRAINT chk_payment_reconciliation_outbox_max_retry CHECK (max_retry >= 0),
    CONSTRAINT chk_payment_reconciliation_outbox_next_retry CHECK ((status = 'PENDING' AND next_retry_at IS NOT NULL) OR status <> 'PENDING'),
    CONSTRAINT chk_payment_reconciliation_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_payment_reconciliation_outbox_status CHECK (status IN ('PENDING', 'RESOLVED', 'FAILED_PERMANENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='외부 PG 상태 미확정(UNKNOWN) 건 재시도용. payment 테이블은 정산.sql 참고';

-- ----------------------------------------------------------------------------
-- refund_request  |  환불 요청. 전액 환불만, 활성 캠페인 보유 시 요청 자체 불가. [2026-10-01] reason 선택으로 완화, reject_reason 컬럼 신규
--   소프트 참조: owner_id -> owner(점주), processed_by -> admin(관리자)
-- ----------------------------------------------------------------------------
CREATE TABLE refund_request (
    refund_request_id  BIGINT        NOT NULL AUTO_INCREMENT,
    owner_id           BIGINT        NOT NULL                               COMMENT '소프트 참조',
    payment_id         BIGINT,
    amount             BIGINT        NOT NULL                               COMMENT '항상 요청 시점 미소진 잔액 전액',
    fee                BIGINT                                               COMMENT '환불 정책상 수수료',
    status             VARCHAR(20)   NOT NULL DEFAULT 'REQUESTED',
    reason             VARCHAR(255)                                         COMMENT '[2026-10-01 완화] 요청 사유. 명세상 선택이라 NOT NULL 해제',
    reject_reason      VARCHAR(255)                                         COMMENT '[2026-10-01 신규] 반려 사유. REJECTED일 때 필수. 감사 로그 전용이 아니라 점주에게 직접 보여주는 값이라 컬럼으로 둠',
    processed_at       DATETIME(6),
    processed_by       BIGINT                                               COMMENT 'admin_id, 소프트 참조',
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    pending_dedup_key  BIGINT        GENERATED ALWAYS AS ((case when (`status` = 'REQUESTED') then `owner_id` else NULL end)) STORED,

    PRIMARY KEY (refund_request_id),
    UNIQUE KEY uk_refund_request_pending (pending_dedup_key),
    KEY fk_refund_request_payment (payment_id),

    CONSTRAINT fk_refund_request_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),

    CONSTRAINT chk_refund_request_amount CHECK (amount > 0),
    CONSTRAINT chk_refund_request_fee CHECK (fee IS NULL OR fee >= 0),
    CONSTRAINT chk_refund_request_processed CHECK ((status = 'REQUESTED' AND processed_at IS NULL AND processed_by IS NULL) OR (status <> 'REQUESTED' AND processed_at IS NOT NULL AND processed_by IS NOT NULL)),
    CONSTRAINT chk_refund_request_reject_reason CHECK ((status = 'REJECTED' AND reject_reason IS NOT NULL) OR (status <> 'REJECTED' AND reject_reason IS NULL)),
    CONSTRAINT chk_refund_request_status CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='환불 요청. 전액 환불만, 활성 캠페인 보유 시 요청 자체 불가. [2026-10-01] reason 선택으로 완화, reject_reason 컬럼 신규';

-- ----------------------------------------------------------------------------
-- point_ledger  |  포인트 원장(append-only). REFUND_INVALID는 존재하지 않음(ADJUST/MANUAL로 대체)
--   소프트 참조: owner_id -> owner(점주), campaign_id -> campaign(캠페인), serve_id -> serve_log(광고 서빙)
-- ----------------------------------------------------------------------------
CREATE TABLE point_ledger (
    point_ledger_id        BIGINT        NOT NULL AUTO_INCREMENT,
    owner_id               BIGINT        NOT NULL                               COMMENT '소프트 참조. 잔액은 이 테이블 SUM으로 직접 계산(별도 잔액 컬럼/캐시 없음, DEDUCT 제외)',
    campaign_id            BIGINT                                               COMMENT '소프트 참조. DEDUCT/REFUND/RESERVE/RELEASE의 캠페인별 breakdown용',
    serve_id               BINARY(16)                                           COMMENT '[2026-10-01 변경, BIGINT -> BINARY(16)] 소프트 참조. DEDUCT만 값 존재. 발급 주체(광고서빙)가 UUID로 구현해 타입을 맞춤',
    payment_id             BIGINT                                               COMMENT 'CHARGE는 항상 값 존재. RESERVE도 값을 가질 수 있음(충전 직후 재예약 트리거). 정기(00:00) RESERVE는 NULL',
    refund_request_id      BIGINT                                               COMMENT 'REFUND만 값 존재',
    business_date          DATE                                                 COMMENT 'RESERVE/RELEASE만 값 존재. RESERVE/RELEASE 대상 영업일자',
    entry_type             VARCHAR(20)   NOT NULL                               COMMENT 'CHARGE/RESERVE/DEDUCT/RELEASE/REFUND/ADJUST. 매일 00:00 1회 RESERVE(잔액에서 실차감) -> 노출마다 DEDUCT(그날 예약액 한도 내 실시간 차감, 잔액엔 미반영) -> 13:30 미소진분 RELEASE(잔액 복원. 00:00에는 전날 해제가 실패해 남은 예약만 정리)',
    source                 VARCHAR(20)   NOT NULL,
    amount                 BIGINT        NOT NULL                               COMMENT '변동량. 부호는 entry_type에 종속',
    reason                 VARCHAR(255)                                         COMMENT 'ADJUST 사유(필수)',
    idempotency_key        VARCHAR(64)                                          COMMENT '[13차 확정] ADJUST 전용 멱등 키(P-19 Idempotency-Key 헤더). 같은 점주와 같은 키의 조정은 1건만 기록',
    created_at             DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6)   NOT NULL,
    day_reserve_dedup_key  VARCHAR(50)   GENERATED ALWAYS AS ((case when ((`entry_type` in ('RESERVE','RELEASE')) and (`payment_id` is null)) then concat(`campaign_id`,'|',`business_date`,'|',`entry_type`) else NULL end)) STORED,

    PRIMARY KEY (point_ledger_id),
    UNIQUE KEY uk_point_ledger_serve_entry (serve_id, entry_type),
    UNIQUE KEY uk_point_ledger_payment_entry (payment_id, entry_type),
    UNIQUE KEY uk_point_ledger_refund_request_entry (refund_request_id, entry_type),
    UNIQUE KEY uk_point_ledger_day_reserve_dedup (day_reserve_dedup_key),
    UNIQUE KEY uk_point_ledger_adjust_idem (owner_id, idempotency_key),
    KEY idx_point_ledger_owner_created (owner_id, created_at),
    KEY idx_point_ledger_campaign (campaign_id),

    CONSTRAINT fk_point_ledger_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),
    CONSTRAINT fk_point_ledger_refund_request FOREIGN KEY (refund_request_id) REFERENCES refund_request (refund_request_id),

    CONSTRAINT chk_point_ledger_adjust_reason CHECK (entry_type <> 'ADJUST' OR reason IS NOT NULL),
    CONSTRAINT chk_point_ledger_adjust_source CHECK (entry_type <> 'ADJUST' OR source = 'MANUAL'),
    CONSTRAINT chk_point_ledger_amount_sign CHECK ((entry_type = 'CHARGE' AND amount > 0) OR (entry_type = 'RESERVE' AND amount < 0) OR (entry_type = 'DEDUCT' AND amount < 0) OR (entry_type = 'RELEASE' AND amount > 0) OR (entry_type = 'REFUND' AND amount < 0) OR (entry_type = 'ADJUST' AND amount <> 0)),
    CONSTRAINT chk_point_ledger_business_date CHECK ((entry_type IN ('RESERVE', 'RELEASE') AND business_date IS NOT NULL) OR (entry_type NOT IN ('RESERVE', 'RELEASE') AND business_date IS NULL)),
    CONSTRAINT chk_point_ledger_entry_type CHECK (entry_type IN ('CHARGE', 'RESERVE', 'DEDUCT', 'RELEASE', 'REFUND', 'ADJUST')),
    CONSTRAINT chk_point_ledger_idempotency_key CHECK (idempotency_key IS NULL OR entry_type = 'ADJUST'),
    CONSTRAINT chk_point_ledger_payment_id CHECK ((entry_type = 'CHARGE' AND payment_id IS NOT NULL) OR entry_type = 'RESERVE' OR (entry_type NOT IN ('CHARGE', 'RESERVE') AND payment_id IS NULL)),
    CONSTRAINT chk_point_ledger_refund_request_id CHECK ((entry_type = 'REFUND' AND refund_request_id IS NOT NULL) OR (entry_type <> 'REFUND' AND refund_request_id IS NULL)),
    CONSTRAINT chk_point_ledger_serve_id CHECK ((entry_type = 'DEDUCT' AND serve_id IS NOT NULL) OR (entry_type <> 'DEDUCT' AND serve_id IS NULL)),
    CONSTRAINT chk_point_ledger_source CHECK (source IN ('REALTIME', 'RECONCILIATION', 'MANUAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='포인트 원장(append-only). REFUND_INVALID는 존재하지 않음(ADJUST/MANUAL로 대체)';

-- ----------------------------------------------------------------------------
-- settlement_mismatch  |  정산 대사 불일치
--   소프트 참조: resolved_by -> admin(관리자)
-- ----------------------------------------------------------------------------
CREATE TABLE settlement_mismatch (
    settlement_mismatch_id  BIGINT       NOT NULL AUTO_INCREMENT,
    check_type              VARCHAR(30)  NOT NULL,
    target_id               BIGINT       NOT NULL                               COMMENT '소프트 참조',
    expected_value          BIGINT       NOT NULL,
    actual_value            BIGINT       NOT NULL,
    resolution_type         VARCHAR(30),
    job_execution_id        BIGINT                                              COMMENT 'BatchExecutionLog(운영 도메인) 소프트 참조. Spring Batch 메타테이블 아님',
    resolved_at             DATETIME(6),
    resolved_by             BIGINT                                              COMMENT 'admin_id, 소프트 참조',
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NOT NULL,

    PRIMARY KEY (settlement_mismatch_id),

    CONSTRAINT chk_settlement_mismatch_check_type CHECK (check_type IN ('PG_VS_CHARGE', 'CAMPAIGN_SPEND_VS_LEDGER')),
    CONSTRAINT chk_settlement_mismatch_resolution_type CHECK (resolution_type IS NULL OR resolution_type IN ('CACHE_REBUILD', 'LEDGER_CORRECTION', 'OVERCHARGE_REVERSAL')),
    CONSTRAINT chk_settlement_mismatch_resolved CHECK ((resolution_type IS NULL AND resolved_at IS NULL AND resolved_by IS NULL) OR (resolution_type IS NOT NULL AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='정산 대사 불일치';

-- ----------------------------------------------------------------------------
-- budget_daily_plan  |  [2026-10-04] 캠페인 도메인에서 정산 도메인으로 이동, 이름 변경. 00:00 배치가 만드는 일자별 읽기전용 스냅샷. 캠페인의 당일 daily_budget을 그대로 찍어두므로, "오늘 값 vs 내일 값" 비교가 필요하면 이 테이블의 오늘 행 vs 캠페인 엔티티의 현재 daily_budget 값을 비교하면 됨(별도 컬럼 불필요)
--   소프트 참조: campaign_id -> campaign(캠페인)
-- ----------------------------------------------------------------------------
CREATE TABLE budget_daily_plan (
    budget_daily_plan_id  BIGINT  NOT NULL AUTO_INCREMENT,
    campaign_id             BIGINT  NOT NULL                 COMMENT '캠페인 ID. 소프트 참조(캠페인 도메인)',
    owner_id                BIGINT  NOT NULL                 COMMENT '점주 ID. 소프트 참조. 00:00 예약 때 캠페인 조회로 받아 둔다. 노출 차감의 원장 owner_id',
    business_date           DATE    NOT NULL,
    daily_budget            BIGINT  NOT NULL,
    unit_price              BIGINT  NOT NULL,
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NOT NULL,

    PRIMARY KEY (budget_daily_plan_id),
    UNIQUE KEY uk_budget_daily_plan_campaign_date (campaign_id, business_date),


    CONSTRAINT chk_budget_daily_plan_daily_budget CHECK (daily_budget > 0),
    CONSTRAINT chk_budget_daily_plan_unit_price CHECK (unit_price > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='00:00 배치가 만드는 일자별 읽기전용 스냅샷. 캠페인의 당일 daily_budget을 그대로 찍어두므로, "오늘 값 vs 내일 값" 비교가 필요하면 이 테이블의 오늘 행 vs 캠페인 엔티티의 현재 daily_budget 값을 비교하면 됨(별도 컬럼 불필요)';

-- ----------------------------------------------------------------------------
-- budget_hourly_target  |  시간대별 목표 소진 포인트(페이싱). [2026-10-04] 캠페인 도메인에서 정산 도메인으로 이동, 이름 변경
-- ----------------------------------------------------------------------------
CREATE TABLE budget_hourly_target (
    budget_hourly_target_id  BIGINT   NOT NULL AUTO_INCREMENT,
    budget_daily_plan_id     BIGINT   NOT NULL                 COMMENT '같은 도메인 테이블(budget_daily_plan) 참조. 실제 FK',
    hour                       TINYINT  NOT NULL,
    target_amount              BIGINT   NOT NULL,
    created_at                 DATETIME(6)  NOT NULL,
    updated_at                 DATETIME(6)  NOT NULL,

    PRIMARY KEY (budget_hourly_target_id),
    UNIQUE KEY uk_budget_hourly_target_plan_hour (budget_daily_plan_id, hour),

    CONSTRAINT fk_budget_hourly_target_daily_plan FOREIGN KEY (budget_daily_plan_id) REFERENCES budget_daily_plan (budget_daily_plan_id),

    CONSTRAINT chk_budget_hourly_target_hour CHECK (hour BETWEEN 0 AND 23),
    CONSTRAINT chk_budget_hourly_target_amount CHECK (target_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='시간대별 목표 소진 포인트(페이싱)';

-- ----------------------------------------------------------------------------
-- deduction_result  |  노출 차감 판정 기록(103행)
--   유효 노출 하나에 한 행. 노출 저장과 같은 트랜잭션에서 쓰고, BILLED면 point_ledger에 DEDUCT도 함께 쓴다
--   쿠폰 소진(NOT_BILLED_SOLD_OUT)은 차감을 부르지 않으므로 여기 없고 impression_log.sold_out에 있다
--   리포트는 분석 배치가 serve_id로 impression_log와 합친다
--   소프트 참조: serve_id -> impression_log(광고 서빙), campaign_id -> campaign(캠페인), owner_id -> owner(점주)
-- ----------------------------------------------------------------------------
CREATE TABLE deduction_result (
    deduction_result_id  BIGINT        NOT NULL AUTO_INCREMENT,
    serve_id             BINARY(16)    NOT NULL,
    campaign_id          BIGINT        NOT NULL,
    owner_id             BIGINT        NOT NULL,
    business_date        DATE          NOT NULL                 COMMENT '노출 영업일',
    billing_status       VARCHAR(20)   NOT NULL                 COMMENT 'BILLED/NOT_BILLED_BUDGET/NOT_BILLED_PAUSED',
    unit_price           BIGINT        NOT NULL                 COMMENT '그날 단가(budget_daily_plan.unit_price). BILLED면 DEDUCT 금액',
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,

    PRIMARY KEY (deduction_result_id),
    UNIQUE KEY uk_deduction_result_serve (serve_id),
    KEY idx_deduction_result_campaign_date (campaign_id, business_date, billing_status),

    CONSTRAINT chk_deduction_result_status CHECK (billing_status IN ('BILLED', 'NOT_BILLED_BUDGET', 'NOT_BILLED_PAUSED')),
    CONSTRAINT chk_deduction_result_unit_price CHECK (unit_price > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='노출 차감 판정 기록. serve_id마다 한 번만';
