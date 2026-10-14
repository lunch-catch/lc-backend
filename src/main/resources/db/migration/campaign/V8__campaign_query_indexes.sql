-- ----------------------------------------------------------------------------
-- campaign  |  CampaignQueryService 조회용 인덱스 (2026-10-12)
--   findByStoreIdIn은 다른 도메인이 요청마다 부르는 조회라 store_id 전체 스캔을 피해야 한다.
--   findServableOn(00:00 적재)은 status, start_date, end_date 세 조건을 함께 걸어
--   영업일이 집행 기간에 든 캠페인을 좁힌다.
-- ----------------------------------------------------------------------------
ALTER TABLE campaign
    ADD KEY idx_campaign_store_id (store_id),
    ADD KEY idx_campaign_status_period (status, start_date, end_date);
