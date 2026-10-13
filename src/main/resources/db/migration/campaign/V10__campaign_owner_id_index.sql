-- ----------------------------------------------------------------------------
-- campaign  |  owner_id 조회용 인덱스 (2026-10-13)
--   hasLiveCampaign, findOwnerIdsWithLiveCampaign, findByOwnerIdAndStatusIn이
--   owner_id로 거른다. 인덱스가 없으면 매번 전체 스캔이라, 점주 락 안에서 불려야 하는
--   hasLiveCampaign이 "exists 수준으로 가볍다"는 요구를 못 지킨다(#198).
-- ----------------------------------------------------------------------------
ALTER TABLE campaign
    ADD KEY idx_campaign_owner_id (owner_id);
