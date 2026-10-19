ALTER TABLE event_log
    ADD INDEX idx_event_log_campaign_occurred_type (campaign_id, occurred_at, event_type),
    ALGORITHM=INPLACE,
    LOCK=NONE;
