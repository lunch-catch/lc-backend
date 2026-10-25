-- ----------------------------------------------------------------------------
-- campaign_daily_slot_report  |  슬롯 이름표 목록을 고정하지 않는다 (2026-10-25, #978)
--   광고 서빙이 붙인 이름표를 그대로 받아 센다. 새 이름표가 생겨도 이 테이블과 집계 코드를 바꾸지 않는다
--   이름표의 모양만 광고 서빙의 로그와 같은 규칙으로 막는다
-- ----------------------------------------------------------------------------
ALTER TABLE campaign_daily_slot_report
    DROP CHECK chk_campaign_daily_slot_type;

ALTER TABLE campaign_daily_slot_report
    MODIFY COLUMN slot_type VARCHAR(30) NOT NULL COMMENT '광고 서빙이 붙인 슬롯 이름표. 예: ALLOCATION, RELEVANCE',
    ADD CONSTRAINT chk_campaign_daily_slot_type CHECK (REGEXP_LIKE(slot_type, '^[A-Z][A-Z0-9_]*$', 'c'));
