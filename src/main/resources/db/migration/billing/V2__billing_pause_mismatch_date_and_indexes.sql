-- billing V2
-- api 명세서 billing.md 의 「추가해야 할 스키마」를 그대로 옮긴 것이다
-- 공통 규칙(소프트 참조, CHECK, 시각, created_at/updated_at)은 db/migration/README.md 를 따른다
-- 대상: MySQL 8.4, InnoDB, utf8mb4_0900_ai_ci
--
-- MySQL 은 DDL 이 트랜잭션에 묶이지 않는다
-- 중간에서 실패하면 앞 문장은 적용된 채로 남고 Flyway 이력에는 실패로 기록된다
-- 그때는 남은 문장만 손으로 적용하지 않고, 이 파일을 다시 돌릴 수 있는 상태로 되돌린 뒤 재적용한다

-- ----------------------------------------------------------------------------
-- budget_daily_plan  |  장중 일시정지 스냅샷 추가와 예약액 0 허용
--   paused 와 status_changed_at 은 캠페인 상태 변경 수신(I-07)이 쓴다
--   차감 핫패스가 같은 행에서 unit_price 와 owner_id 를 이미 읽으므로 조회가 늘지 않는다
--   daily_budget 은 예약액이 0 인 캠페인도 행을 만들어야 해서 0 을 허용한다
--   같은 이름의 CHECK 는 한 ALTER 에서 DROP 과 ADD 를 함께 하지 못해 문장을 나눈다
-- ----------------------------------------------------------------------------
ALTER TABLE budget_daily_plan DROP CHECK chk_budget_daily_plan_daily_budget;

ALTER TABLE budget_daily_plan
    ADD CONSTRAINT chk_budget_daily_plan_daily_budget CHECK (daily_budget >= 0),
    ADD COLUMN paused BOOLEAN NOT NULL DEFAULT 0 COMMENT '그날 중단 여부. 장중에 갱신된다' AFTER unit_price,
    ADD COLUMN status_changed_at DATETIME(6) NULL                                COMMENT '반영한 캠페인 상태 변경 이벤트의 changedAt. 이보다 늦은 이벤트만 반영한다';

-- 이 테이블은 더 이상 읽기전용 스냅샷이 아니다
-- paused 는 장중에 바뀌고 daily_budget 도 충전 직후 재예약이 갱신한다
ALTER TABLE budget_daily_plan
    COMMENT='00:00 배치가 만드는 일자별 예산 계획. daily_budget 은 그날 예약액이고 충전 직후 재예약이 갱신한다. paused 와 status_changed_at 은 장중 캠페인 상태 변경을 반영한다';

-- ----------------------------------------------------------------------------
-- settlement_mismatch  |  대사 영업일 추가와 재탐지 upsert 용 UNIQUE
--   같은 (check_type, target_id, business_date) 를 다시 탐지하면 새 행을 만들지 않고 갱신한다
--   기존 행이 있을 수 있어 NULL 허용으로 더하고, 채운 뒤 NOT NULL 로 바꾼다
-- ----------------------------------------------------------------------------
ALTER TABLE settlement_mismatch
    ADD COLUMN business_date DATE NULL COMMENT '대사 대상 영업일(Asia/Seoul)' AFTER check_type;

-- 한 번만 도는 메움이다
-- 세션 시간대가 Asia/Seoul 로 고정되어 있어 이 변환 결과가 영업일과 같다
-- 날짜 함수를 쓰지 않는다는 규칙은 애플리케이션 코드를 겨냥한 것이라 이 자리는 대상이 아니다
UPDATE settlement_mismatch SET business_date = DATE(created_at) WHERE business_date IS NULL;

ALTER TABLE settlement_mismatch MODIFY COLUMN business_date DATE NOT NULL        COMMENT '대사 대상 영업일(Asia/Seoul)';

ALTER TABLE settlement_mismatch
    ADD CONSTRAINT uk_settlement_mismatch_check_target_date UNIQUE (check_type, target_id, business_date);

-- ----------------------------------------------------------------------------
-- point_ledger  |  RESERVE, RELEASE, DEDUCT 는 캠페인이 필수다
--   캠페인별 집계가 전부 이 컬럼으로 갈라진다
--   비어 있으면 그 금액이 어느 캠페인 것도 아니게 되어 집계에서 조용히 빠진다
-- ----------------------------------------------------------------------------
ALTER TABLE point_ledger
    ADD CONSTRAINT chk_point_ledger_campaign_id
    CHECK (entry_type NOT IN ('RESERVE', 'RELEASE', 'DEDUCT') OR campaign_id IS NOT NULL);

-- ----------------------------------------------------------------------------
-- 조회 인덱스
--   관리자 결제 목록(상태와 기간), 점주 결제 목록, 환불 목록이 앞의 다섯을 쓴다
--   원장 유형별 조회와 영업일 기준 원장 조회가 뒤의 둘을 쓴다
-- ----------------------------------------------------------------------------
CREATE INDEX idx_payment_status_created ON payment (status, created_at);
CREATE INDEX idx_payment_owner_status ON payment (owner_id, status);
CREATE INDEX idx_payment_owner_created ON payment (owner_id, created_at);
CREATE INDEX idx_refund_request_owner_created ON refund_request (owner_id, created_at);
CREATE INDEX idx_refund_request_status_created ON refund_request (status, created_at);
CREATE INDEX idx_point_ledger_owner_type_created ON point_ledger (owner_id, entry_type, created_at);
CREATE INDEX idx_point_ledger_owner_business_date ON point_ledger (owner_id, business_date);
