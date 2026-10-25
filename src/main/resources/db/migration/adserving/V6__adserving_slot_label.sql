-- ----------------------------------------------------------------------------
-- serve_log, impression_log  |  슬롯을 닫힌 ENUM 이 아니라 엔진이 정하는 이름표로 둔다 (2026-10-25, #978)
--   피드 구성을 바꿔 새 이름표가 생겨도 마이그레이션을 다시 하지 않는다. 지금 엔진은 ALLOCATION, RELEVANCE 를 붙인다
--   이름표는 대문자로 시작하고 대문자, 숫자, 밑줄만 쓴다. serve 기록(Valkey)이 콜론으로 값을 잇기 때문이다
--   ENUM -> VARCHAR 는 테이블을 복사해 그동안 쓰기가 멈춘다. 운영 규모에서는 배포 시간을 따로 잡는다
--   슬롯 자리의 상한(10)은 피드 크기 설정을 따르도록 CHECK 에서 뺀다
-- ----------------------------------------------------------------------------
ALTER TABLE serve_log
    MODIFY COLUMN slot_type VARCHAR(30) NOT NULL COMMENT '피드 엔진이 붙인 슬롯 이름표. 예: ALLOCATION, RELEVANCE',
    DROP CHECK chk_serve_position;

ALTER TABLE serve_log
    ADD CONSTRAINT chk_serve_position CHECK (slot_position >= 1),
    ADD CONSTRAINT chk_serve_slot_type CHECK (REGEXP_LIKE(slot_type, '^[A-Z][A-Z0-9_]*$', 'c'));

ALTER TABLE impression_log
    MODIFY COLUMN slot_type VARCHAR(30) COMMENT '피드 엔진이 붙인 슬롯 이름표. serve 기록이 있을 때만',
    ADD CONSTRAINT chk_imp_slot_type CHECK (slot_type IS NULL OR REGEXP_LIKE(slot_type, '^[A-Z][A-Z0-9_]*$', 'c'));
