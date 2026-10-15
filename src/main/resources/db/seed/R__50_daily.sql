/*
 * 날짜에 묶인 행이다.
 * 피드와 쿠폰은 오늘(business_date) 행만 읽는다.
 * ${seedBusinessDate} 가 하루마다 바뀌어 이 파일의 체크섬도 바뀌고, 하루의 첫 기동에 다시 돈다.
 * 원래는 00:00 배치가 만들 행이다.
 * 배치가 생기면 그 배치가 덮는다.
 */
/*
 * 예산이다.
 * 피드는 이 행이 없으면 후보를 뺀다.
 * 노출 단가는 ops V3 의 IMPRESSION_UNIT_PRICE(10)와 맞춘다.
 */
INSERT INTO budget_daily_plan (campaign_id, owner_id, business_date, daily_budget, unit_price, paused,
                               created_at, updated_at)
VALUES (9001, 9001, '${seedBusinessDate}', 10000, 10, 0, NOW(6), NOW(6)),
       (9002, 9002, '${seedBusinessDate}', 10000, 10, 0, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE campaign_id = campaign_id;

-- 피드가 읽는 후보다
INSERT INTO ad_candidate (business_date, campaign_id, store_id, store_name, store_image_url, category_code,
                          latitude, longitude, target_radius, target_gender, target_age_groups,
                          discount_target_type, discount_type, discount_value, discount_menu_name,
                          usable_start_time, usable_end_time, issue_quantity, campaign_created_at,
                          new_priority_until, campaign_state, state_changed_at, sold_out_at, created_at, updated_at)
VALUES ('${seedBusinessDate}', 9001, 9001, '런치 한식당', NULL, 'KOREAN', 37.4979000, 127.0276000, 1000, 'ALL',
        'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 'ALL', 'AMOUNT', 2000, NULL, '11:00', '14:00', 50, NOW(6),
        NULL, 'SERVABLE', NULL, NULL, NOW(6), NOW(6)),
       ('${seedBusinessDate}', 9002, 9002, '런치 분식', NULL, 'BUNSIK', 37.5006000, 127.0364000, 1000, 'ALL',
        'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 'MENU', 'PERCENT', 20, '떡볶이', '11:00', '14:00', 50, NOW(6),
        NULL, 'SERVABLE', NULL, NULL, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE campaign_id = campaign_id;

-- 쿠폰 이벤트는 주문 전체 할인을 금액(AMOUNT)으로만 받는다 (chk_coupon_event_discount)
INSERT INTO coupon_event (campaign_id, business_date, issue_quantity, usable_start_at, usable_end_at, status,
                          store_id, store_name, store_image_url, discount_target_type, discount_type, discount_value,
                          target_menu_id, target_menu_name, target_menu_price, target_menu_discounted_price,
                          created_at, updated_at)
VALUES (9001, '${seedBusinessDate}', 50, TIMESTAMP('${seedBusinessDate}', '11:00:00'),
        TIMESTAMP('${seedBusinessDate}', '14:00:00'), 'ACTIVE', 9001, '런치 한식당', NULL, 'ALL', 'AMOUNT', 2000,
        NULL, NULL, NULL, NULL, NOW(6), NOW(6)),
       (9002, '${seedBusinessDate}', 50, TIMESTAMP('${seedBusinessDate}', '11:00:00'),
        TIMESTAMP('${seedBusinessDate}', '14:00:00'), 'ACTIVE', 9002, '런치 분식', NULL, 'MENU', 'PERCENT', 20,
        9004, '떡볶이', 6000, 4800, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE coupon_event_id = coupon_event_id;

/*
 * 쿠폰 재고다.
 * 이벤트마다 1~50 번을 만든다.
 * 코드는 이벤트와 번호로 정해져 다시 돌아도 같다.
 */
INSERT INTO coupon_inventory (coupon_event_id, sequence_no, coupon_code, status, usable_start_at, usable_end_at,
                              created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 50)
SELECT ce.coupon_event_id, seq.n, UNHEX(MD5(CONCAT('seed-', ce.coupon_event_id, '-', seq.n))), 'AVAILABLE',
       ce.usable_start_at, ce.usable_end_at, NOW(6), NOW(6)
FROM coupon_event ce
CROSS JOIN seq
WHERE ce.campaign_id IN (9001, 9002) AND ce.business_date = '${seedBusinessDate}'
ON DUPLICATE KEY UPDATE coupon_inventory_id = coupon_inventory_id;
