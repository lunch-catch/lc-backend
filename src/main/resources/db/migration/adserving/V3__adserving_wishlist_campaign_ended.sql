-- ----------------------------------------------------------------------------
-- wishlist  |  끝난 캠페인의 찜을 3일 동안 남겨 표시 (2026-10-19, #548, #553)
--   캠페인이 끝나면 행을 지우지 않고 종료 시각을 채운다. 응답의 campaignEnded 가 이 값으로 정해진다
--   끝난 날을 빼고 3일이 지나면 00:00 작업이 지운다. 그 작업이 idx_wishlist_campaign_ended 로 지울 행을 찾는다
-- ----------------------------------------------------------------------------
ALTER TABLE wishlist
    ADD COLUMN campaign_ended_at DATETIME(6) COMMENT '캠페인이 끝난 시각(CampaignEndedEvent.changedAt). 진행 중이면 NULL' AFTER serve_id,
    ADD KEY idx_wishlist_campaign_ended (campaign_ended_at),
    COMMENT = '사용자 삭제 시 행 삭제. 캠페인 종료 시 종료 시각을 채우고 끝난 날을 빼고 3일 뒤 삭제';
