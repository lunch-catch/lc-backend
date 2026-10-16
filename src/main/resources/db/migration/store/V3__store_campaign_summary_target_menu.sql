ALTER TABLE store_campaign_summary
    ADD COLUMN target_menu_id BIGINT NULL COMMENT 'MENU 할인 대상 메뉴 ID' AFTER discount_value,
    ADD CONSTRAINT fk_store_campaign_summary_target_menu
        FOREIGN KEY (target_menu_id) REFERENCES store_menu (store_menu_id),
    ADD INDEX idx_store_campaign_summary_store_date_status (store_id, business_date, campaign_status);
