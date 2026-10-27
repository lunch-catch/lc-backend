ALTER TABLE coupon_daily_limit
    DROP CHECK chk_coupon_daily_limit_count,
    ADD CONSTRAINT chk_coupon_daily_limit_count CHECK (issued_count >= 0);
