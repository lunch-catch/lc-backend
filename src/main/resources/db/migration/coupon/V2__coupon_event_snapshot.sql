/*
 * 기존 이벤트가 있으면 과거 스냅샷 보완 방안을 먼저 확정해야 한다.
 * 컬럼과 CHECK를 한 ALTER에 적용하여 미완성 스냅샷이나 임의 기본값이 남지 않게 한다.
 */
ALTER TABLE coupon_event
    ADD COLUMN store_id BIGINT NOT NULL,
    ADD COLUMN store_name VARCHAR(100) NOT NULL,
    ADD COLUMN store_image_object_key VARCHAR(255),
    ADD COLUMN discount_target_type VARCHAR(20) NOT NULL,
    ADD COLUMN discount_type VARCHAR(20) NOT NULL,
    ADD COLUMN discount_value INT NOT NULL,
    ADD COLUMN target_menu_id BIGINT,
    ADD COLUMN target_menu_name VARCHAR(100),
    ADD COLUMN target_menu_price BIGINT,
    ADD COLUMN target_menu_discounted_price BIGINT,
    DROP COLUMN status_changed_at,
    MODIFY COLUMN status VARCHAR(20) NOT NULL COMMENT 'SCHEDULED/ACTIVE/PAUSED/ENDED',
    ADD KEY idx_coupon_event_store_date (store_id, business_date),
    ADD CONSTRAINT chk_coupon_event_store_snapshot
        CHECK (store_id > 0 AND CHAR_LENGTH(TRIM(store_name)) > 0),
    ADD CONSTRAINT chk_coupon_event_discount_combination
        CHECK ((discount_target_type = 'MENU' AND discount_type IN ('PERCENT', 'AMOUNT'))
            OR (discount_target_type = 'ALL' AND discount_type = 'AMOUNT')),
    ADD CONSTRAINT chk_coupon_event_discount_value
        CHECK (discount_value > 0 AND (discount_type <> 'PERCENT' OR discount_value <= 100)),
    ADD CONSTRAINT chk_coupon_event_menu_snapshot
        CHECK ((discount_target_type = 'MENU'
                AND target_menu_id IS NOT NULL AND target_menu_id > 0
                AND target_menu_name IS NOT NULL AND CHAR_LENGTH(TRIM(target_menu_name)) > 0
                AND target_menu_price IS NOT NULL AND target_menu_price >= 0
                AND target_menu_discounted_price IS NOT NULL
                AND target_menu_discounted_price BETWEEN 0 AND target_menu_price)
            OR (discount_target_type = 'ALL'
                AND target_menu_id IS NULL AND target_menu_name IS NULL
                AND target_menu_price IS NULL AND target_menu_discounted_price IS NULL));
