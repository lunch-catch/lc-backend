-- ----------------------------------------------------------------------------
-- platform_setting  |  IMPRESSION_UNIT_PRICE 시드 (2026-10-13)
--   00:00 예약 배치가 budget_daily_plan.unit_price 에 쓸 노출 단가다. 팀장 결정 전이라
--   개발 기본값 10으로 둔다. #198
-- ----------------------------------------------------------------------------
INSERT INTO platform_setting (setting_key, setting_value, description, created_at, updated_at)
VALUES ('IMPRESSION_UNIT_PRICE', '10', '노출 1건당 단가(원). 팀장 결정 전 개발 기본값',
        '2026-10-13 00:00:00.000000', '2026-10-13 00:00:00.000000');
