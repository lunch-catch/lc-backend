-- 전체 메뉴 정률을 제외하고 메뉴 한 개에 적용하는 세 할인 조합만 허용한다.
ALTER TABLE coupon_event
    DROP CHECK chk_coupon_event_discount_combination,
    ADD CONSTRAINT chk_coupon_event_discount_combination
        CHECK ((discount_target_type = 'MENU' AND discount_type IN ('PERCENT', 'AMOUNT'))
            OR (discount_target_type = 'ALL' AND discount_type = 'AMOUNT'));
