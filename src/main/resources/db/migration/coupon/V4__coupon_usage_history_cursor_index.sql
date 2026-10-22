-- 매장별 사용 시각과 이력 ID 순서로 조회하는 커서 목록에 맞춰 기존 매장 인덱스를 교체한다.
ALTER TABLE coupon_usage_history
    DROP INDEX idx_coupon_usage_history_store,
    ADD INDEX idx_coupon_usage_history_store_time (store_id, created_at, coupon_usage_history_id),
    ALGORITHM=INPLACE,
    LOCK=NONE;
