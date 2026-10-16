-- ----------------------------------------------------------------------------
-- campaign  |  ACTIVE에서 바꾼 하루 예산의 다음 영업일 적용 예약 값 (#330)
--   dailyBudget 자체는 그대로 두고 이 컬럼에만 써서 그날 서빙에 영향을 주지 않는다.
--   00:00 배치가 이 값을 dailyBudget에 반영하는 것은 그 배치 이슈 몫이라 여기서는
--   컬럼만 추가한다. daily_budget과 같은 이유로 양수만 허용한다(chk_campaign_quantity_budget).
-- ----------------------------------------------------------------------------
ALTER TABLE campaign
    ADD COLUMN pending_daily_budget BIGINT COMMENT '포인트 단위. 다음 영업일 00:00 배치가 적용할 예약 값' AFTER daily_budget,
    ADD CONSTRAINT chk_campaign_pending_daily_budget CHECK (pending_daily_budget IS NULL OR pending_daily_budget > 0);
