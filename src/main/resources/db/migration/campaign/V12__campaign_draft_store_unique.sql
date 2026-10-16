-- ----------------------------------------------------------------------------
-- campaign  |  가게당 DRAFT 캠페인 1건을 DB 제약으로도 보장한다 (#244)
--   37행 active_store_marker/uk_campaign_active_store(가게당 ACTIVE 1건)와 같은 방식이다.
--   애플리케이션 레벨의 조회(findByStoreIdAndStatus)만으로는 동시에 들어온 두 생성 요청이
--   DRAFT 2개를 만드는 경쟁을 막지 못해 DB UNIQUE로도 건다.
-- ----------------------------------------------------------------------------
ALTER TABLE campaign
    ADD COLUMN draft_store_marker BIGINT
        GENERATED ALWAYS AS ((case when (`status` = 'DRAFT') then `store_id` else NULL end)) STORED
        COMMENT '가게당 DRAFT 캠페인 1건 UNIQUE 제약용. DRAFT일 때만 store_id, 그 외 NULL',
    ADD UNIQUE KEY uk_campaign_draft_store (draft_store_marker);
