-- ----------------------------------------------------------------------------
-- campaign_status_log  |  campaign_id, created_at 복합 인덱스 (#354)
--   CampaignStatusLogRepository.countActiveAsOf가 캠페인마다 cutoff 이전 마지막 전이
--   행을 윈도 함수로 찾는다. campaign_id 단일 인덱스(FK용)만 있으면 created_at 조건도
--   파티션 안 정렬도 전부 풀스캔과 filesort라, 로그가 쌓일수록 이 집계가 느려진다.
-- ----------------------------------------------------------------------------
ALTER TABLE campaign_status_log
    ADD KEY idx_campaign_status_log_campaign_created (campaign_id, created_at);
