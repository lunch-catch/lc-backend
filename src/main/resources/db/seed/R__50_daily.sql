/*
 * 날짜에 묶인 행이다.
 * 피드와 쿠폰은 오늘(business_date) 행만 읽는다.
 * ${seedBusinessDate} 가 하루마다 바뀌어 이 파일의 체크섬도 바뀌고, 하루의 첫 기동에 다시 돈다.
 * 원래는 00:00 배치가 만들 행이다.
 * 배치가 생기면 그 배치가 덮는다.
 */
/*
 * 예산이다.
 * 피드는 이 행이 없으면 후보를 뺀다. 9016 은 그것을 보려고 일부러 넣지 않는다.
 * 노출 단가는 ops V3 의 IMPRESSION_UNIT_PRICE(10)와 맞춘다.
 */
INSERT INTO budget_daily_plan (campaign_id, owner_id, business_date, daily_budget, unit_price, paused,
                               created_at, updated_at)
VALUES (9001, 9001, '${seedBusinessDate}', 10000, 10, 0, NOW(6), NOW(6)),
       (9002, 9002, '${seedBusinessDate}', 10000, 10, 0, NOW(6), NOW(6)),
       (9003, 9003, '${seedBusinessDate}', 10000, 10, 0, NOW(6), NOW(6)),
       (9004, 9004, '${seedBusinessDate}', 10000, 10, 0, NOW(6), NOW(6)),
       (9005, 9005, '${seedBusinessDate}', 10000, 10, 0, NOW(6), NOW(6)),
       (9006, 9006, '${seedBusinessDate}', 10000, 10, 0, NOW(6), NOW(6)),
       (9007, 9007, '${seedBusinessDate}', 10000, 10, 0, NOW(6), NOW(6)),
       (9008, 9008, '${seedBusinessDate}', 10000, 10, 0, NOW(6), NOW(6)),
       (9009, 9009, '${seedBusinessDate}', 10000, 10, 0, NOW(6), NOW(6)),
       (9010, 9010, '${seedBusinessDate}', 10000, 10, 0, NOW(6), NOW(6)),
       (9011, 9011, '${seedBusinessDate}', 10000, 10, 0, NOW(6), NOW(6)),
       (9012, 9012, '${seedBusinessDate}', 10000, 10, 0, NOW(6), NOW(6)),
       (9013, 9013, '${seedBusinessDate}', 10000, 10, 0, NOW(6), NOW(6)),
       (9014, 9014, '${seedBusinessDate}', 10000, 10, 0, NOW(6), NOW(6)),
       (9015, 9015, '${seedBusinessDate}', 10000, 10, 0, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE campaign_id = campaign_id;

/*
 * 피드가 읽는 후보다. 거리는 강남역(37.4979, 127.0276) 기준이다.
 * 1 km 안에 전 성별, 전 연령대 대상 후보가 8개(9001~9008)라 탐색 반경이 2000 으로 넓어지고 10장이 찬다.
 * 1~2 km 에 9009~9012 가 있다. 업종 7종과 할인 PERCENT, AMOUNT 를 섞었고 9010 은 메뉴 할인이다.
 * 9013 은 노출 반경 500 m 인데 667 m 에 있어 빠진다. 9014 는 남성, 9015 는 20대만 대상이다. 9016 은 예산 계획이 없어 빠진다.
 * 9014, 9015 를 받는 회원은 1 km 안에 10개가 모여 반경이 1000 으로 남는다.
 * 생성 시각을 1분씩 늦춰 진행률이 같을 때(시드는 소진이 0 이다) 배분 슬롯의 순서가 정해진다.
 */
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
        NULL, 'SERVABLE', NULL, NULL, NOW(6), NOW(6)),
       ('${seedBusinessDate}', 9003, 9003, '런치 스시', NULL, 'JAPANESE', 37.4999000, 127.0276000, 1000, 'ALL',
        'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 'ALL', 'PERCENT', 15, NULL, '11:00', '14:00', 50, NOW(6) + INTERVAL 1 MINUTE,
        NULL, 'SERVABLE', NULL, NULL, NOW(6), NOW(6)),
       ('${seedBusinessDate}', 9004, 9004, '런치 짜장', NULL, 'CHINESE', 37.4954000, 127.0276000, 1000, 'ALL',
        'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 'ALL', 'AMOUNT', 3000, NULL, '11:00', '14:00', 50, NOW(6) + INTERVAL 2 MINUTE,
        NULL, 'SERVABLE', NULL, NULL, NOW(6), NOW(6)),
       ('${seedBusinessDate}', 9005, 9005, '런치 파스타', NULL, 'WESTERN', 37.4979000, 127.0316000, 1000, 'ALL',
        'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 'ALL', 'PERCENT', 10, NULL, '11:00', '14:00', 50, NOW(6) + INTERVAL 3 MINUTE,
        NULL, 'SERVABLE', NULL, NULL, NOW(6), NOW(6)),
       ('${seedBusinessDate}', 9006, 9006, '런치 카페', NULL, 'CAFE_DESSERT', 37.4979000, 127.0231000, 1000, 'ALL',
        'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 'ALL', 'PERCENT', 30, NULL, '11:00', '14:00', 50, NOW(6) + INTERVAL 4 MINUTE,
        NULL, 'SERVABLE', NULL, NULL, NOW(6), NOW(6)),
       ('${seedBusinessDate}', 9007, 9007, '런치 쌀국수', NULL, 'ASIAN', 37.5019000, 127.0276000, 1000, 'ALL',
        'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 'ALL', 'AMOUNT', 1500, NULL, '11:00', '14:00', 50, NOW(6) + INTERVAL 5 MINUTE,
        NULL, 'SERVABLE', NULL, NULL, NOW(6), NOW(6)),
       ('${seedBusinessDate}', 9008, 9008, '런치 버거', NULL, 'FAST_FOOD', 37.4929000, 127.0276000, 1000, 'ALL',
        'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 'ALL', 'PERCENT', 25, NULL, '11:00', '14:00', 50, NOW(6) + INTERVAL 6 MINUTE,
        NULL, 'SERVABLE', NULL, NULL, NOW(6), NOW(6)),
       ('${seedBusinessDate}', 9009, 9009, '런치 국밥', NULL, 'KOREAN', 37.5089000, 127.0276000, 2000, 'ALL',
        'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 'ALL', 'AMOUNT', 2500, NULL, '11:00', '14:00', 50, NOW(6) + INTERVAL 7 MINUTE,
        NULL, 'SERVABLE', NULL, NULL, NOW(6), NOW(6)),
       ('${seedBusinessDate}', 9010, 9010, '런치 라멘', NULL, 'JAPANESE', 37.4849000, 127.0276000, 2000, 'ALL',
        'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 'MENU', 'PERCENT', 20, '돈코츠 라멘', '11:00', '14:00', 50, NOW(6) + INTERVAL 8 MINUTE,
        NULL, 'SERVABLE', NULL, NULL, NOW(6), NOW(6)),
       ('${seedBusinessDate}', 9011, 9011, '런치 마라탕', NULL, 'CHINESE', 37.4979000, 127.0456000, 2000, 'ALL',
        'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 'ALL', 'PERCENT', 15, NULL, '11:00', '14:00', 50, NOW(6) + INTERVAL 9 MINUTE,
        NULL, 'SERVABLE', NULL, NULL, NOW(6), NOW(6)),
       ('${seedBusinessDate}', 9012, 9012, '런치 스테이크', NULL, 'WESTERN', 37.4979000, 127.0126000, 2000, 'ALL',
        'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 'ALL', 'AMOUNT', 4000, NULL, '11:00', '14:00', 50, NOW(6) + INTERVAL 10 MINUTE,
        NULL, 'SERVABLE', NULL, NULL, NOW(6), NOW(6)),
       ('${seedBusinessDate}', 9013, 9013, '런치 백반', NULL, 'KOREAN', 37.5039000, 127.0276000, 500, 'ALL',
        'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 'ALL', 'AMOUNT', 1000, NULL, '11:00', '14:00', 50, NOW(6) + INTERVAL 11 MINUTE,
        NULL, 'SERVABLE', NULL, NULL, NOW(6), NOW(6)),
       ('${seedBusinessDate}', 9014, 9014, '런치 순대', NULL, 'BUNSIK', 37.4969000, 127.0276000, 1000, 'MALE',
        'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 'ALL', 'AMOUNT', 1000, NULL, '11:00', '14:00', 50, NOW(6) + INTERVAL 12 MINUTE,
        NULL, 'SERVABLE', NULL, NULL, NOW(6), NOW(6)),
       ('${seedBusinessDate}', 9015, 9015, '런치 디저트', NULL, 'CAFE_DESSERT', 37.4979000, 127.0291000, 1000, 'ALL',
        'AGE_20S', 'ALL', 'PERCENT', 10, NULL, '11:00', '14:00', 50, NOW(6) + INTERVAL 13 MINUTE,
        NULL, 'SERVABLE', NULL, NULL, NOW(6), NOW(6)),
       ('${seedBusinessDate}', 9016, 9016, '런치 도시락', NULL, 'OTHER', 37.4989000, 127.0276000, 1000, 'ALL',
        'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 'ALL', 'AMOUNT', 1000, NULL, '11:00', '14:00', 50, NOW(6) + INTERVAL 14 MINUTE,
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
