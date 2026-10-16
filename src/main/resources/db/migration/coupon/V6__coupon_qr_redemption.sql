-- 현재 QR과 사용 완료 QR의 토큰으로 회원 쿠폰을 찾을 때 전체 쿠폰을 훑지 않도록 한다.
ALTER TABLE member_coupon
    ADD INDEX idx_member_coupon_qr_token (qr_token),
    ALGORITHM=INPLACE,
    LOCK=NONE;

-- 기존 할인 조합과 스냅샷을 유지하면서 전체 주문 정률 할인을 허용한다.
ALTER TABLE coupon_event
    DROP CHECK chk_coupon_event_discount_combination,
    ADD CONSTRAINT chk_coupon_event_discount_combination
        CHECK (discount_target_type IN ('MENU', 'ALL') AND discount_type IN ('PERCENT', 'AMOUNT'));
