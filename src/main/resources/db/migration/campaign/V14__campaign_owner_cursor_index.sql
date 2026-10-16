-- ----------------------------------------------------------------------------
-- campaign  |  점주 캠페인 목록 커서 페이지네이션용 복합 인덱스 (#341)
--   findPageByOwnerId가 owner_id 단일 인덱스(idx_campaign_owner_id)만으로는 커서
--   조건(campaign_id < ?)과 만나 index_merge(idx_campaign_owner_id, PRIMARY) + filesort로
--   빠진다(실측 확인). owner_id와 campaign_id를 묶은 인덱스를 두고 쿼리에서 FORCE INDEX로
--   명시해야 filesort 없는 range 스캔이 된다.
-- ----------------------------------------------------------------------------
ALTER TABLE campaign
    ADD KEY idx_campaign_owner_cursor (owner_id, campaign_id);
