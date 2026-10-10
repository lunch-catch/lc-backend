-- billing V3
-- 구현하면서 정한 세 가지다
-- V2 는 명세에 이미 있던 변경이고 이 파일은 그 뒤에 온다
-- 대상: MySQL 8.4, InnoDB, utf8mb4_0900_ai_ci

-- ----------------------------------------------------------------------------
-- 1. settlement_mismatch  |  시스템이 닫은 해결에는 관리자 ID 가 없다
--   resolution_type 셋 중 CACHE_REBUILD 와 LEDGER_CORRECTION 은 배치가 기록한다
--   그 둘에 resolved_by 를 요구하면 배치가 자동 복구를 남길 수 없다
--   남기지 못하면 복구가 끝난 건이 미해결 목록에 영구히 쌓인다
--   사람이 처리하는 OVERCHARGE_REVERSAL 만 관리자 ID 를 요구한다
--   같은 이름의 CHECK 는 한 ALTER 에서 DROP 과 ADD 를 함께 하지 못해 문장을 나눈다
-- ----------------------------------------------------------------------------
ALTER TABLE settlement_mismatch DROP CHECK chk_settlement_mismatch_resolved;

ALTER TABLE settlement_mismatch
    ADD CONSTRAINT chk_settlement_mismatch_resolved
        CHECK ((resolution_type IS NULL AND resolved_at IS NULL AND resolved_by IS NULL)
            OR (resolution_type IS NOT NULL AND resolved_at IS NOT NULL)),
    ADD CONSTRAINT chk_settlement_mismatch_resolved_by
        CHECK (resolution_type IS NULL
            OR resolution_type <> 'OVERCHARGE_REVERSAL'
            OR resolved_by IS NOT NULL);

ALTER TABLE settlement_mismatch
    MODIFY COLUMN resolved_by BIGINT NULL                                        COMMENT 'admin_id, 소프트 참조. 배치가 자동 복구한 건은 NULL';

-- ----------------------------------------------------------------------------
-- 2. point_account  |  점주당 한 행이며 잔액을 읽고 쓰는 작업의 직렬화 기준이다
--   환불 승인, 음수 조정, 00:00 예약, 충전 직후 재예약이 이 행을 잠그고 잔액을 읽는다
--   넷 모두 읽기와 쓰기 사이에 다른 요청이 끼면 갱신 손실이 된다
--   정산은 점주 테이블을 잠글 수 없어서 기준 행이 이 도메인 안에 있어야 한다
--   잔액은 컬럼으로 두지 않는다
--   유일한 출처는 point_ledger 의 합이고, 여기 숫자를 두면 두 값이 갈릴 자리가 생긴다
--   대리키를 두고 owner_id 에 UNIQUE 를 거는 것은 공통 베이스 엔티티를 그대로 쓰기 위해서다
--   소프트 참조: owner_id -> owner(점주)
-- ----------------------------------------------------------------------------
CREATE TABLE point_account (
    point_account_id  BIGINT       NOT NULL AUTO_INCREMENT,
    owner_id          BIGINT       NOT NULL                                      COMMENT '소프트 참조. 점주당 1행',
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,

    PRIMARY KEY (point_account_id),
    UNIQUE KEY uk_point_account_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='점주 포인트 계정. 잔액은 담지 않는다(원장 합이 유일한 출처). 잔액을 읽고 쓰는 작업의 점주 단위 직렬화 기준 행';

-- ----------------------------------------------------------------------------
-- 3. point_policy  |  최초 정책 한 행
--   정책 행이 없으면 점주 충전 화면이 최소 충전 금액과 상품 목록을 받을 수 없다
--   관리자 저장은 항상 내일부터 적용되므로 빈 DB 에서는 오늘 충전할 방법이 생기지 않는다
--   created_by 는 관리자 ID 라 시스템이 넣는 이 행에는 넣을 값이 없다
--   0 같은 없는 ID 를 적어 두면 나중에 관리자 목록과 맞춰 볼 때 빈 결과가 나온다
--   그래서 NULL 을 허용한다
--   금액은 명세의 응답 예시 값이다
--   다르게 정하면 관리자 정책 저장 API 로 바꾼다
-- ----------------------------------------------------------------------------
ALTER TABLE point_policy
    MODIFY COLUMN created_by BIGINT NULL                                         COMMENT 'admin_id, 소프트 참조. 마이그레이션이 넣은 최초 정책 행만 NULL';

-- 적용일을 과거 고정값으로 둔다
-- 이 파일이 언제 적용되든 "오늘 이하" 를 만족해야 한다
INSERT INTO point_policy
    (min_charge_amount, charge_products, change_reason, effective_date, created_by, created_at, updated_at)
VALUES
    (10000, JSON_ARRAY(10000, 30000, 50000, 100000), '최초 정책', '2026-01-01',
     NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));
