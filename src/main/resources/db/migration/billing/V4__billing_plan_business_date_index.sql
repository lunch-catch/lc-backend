-- ----------------------------------------------------------------------------
-- budget_daily_plan  |  영업일로 계획을 찾는 인덱스 (2026-10-15, #306)
--   00:00 단계가 전일 계획(대사, 보정 RELEASE)과 그날의 중단되지 않은 계획(시간대별 목표)을 영업일로 읽는다.
--   uk_budget_daily_plan_campaign_date 는 campaign_id 가 앞이라 영업일만으로는 타지 못하고 테이블 전체를 읽는다.
-- ----------------------------------------------------------------------------
CREATE INDEX idx_budget_daily_plan_business_date ON budget_daily_plan (business_date);
