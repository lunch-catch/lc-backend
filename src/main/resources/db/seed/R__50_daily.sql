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

-- 피드 시험 캠페인 9101~9200 의 예산이다(#611). 9105~9107 은 예산 계획이 없어 빠지는 경우라 넣지 않는다
INSERT INTO budget_daily_plan (campaign_id, owner_id, business_date, daily_budget, unit_price, paused,
                               created_at, updated_at)
SELECT c.campaign_id, c.owner_id, '${seedBusinessDate}', 10000, 10, IF(c.status = 'PAUSED', 1, 0), NOW(6), NOW(6)
FROM campaign c
WHERE c.campaign_id BETWEEN 9101 AND 9200 AND c.campaign_id NOT BETWEEN 9105 AND 9107
ON DUPLICATE KEY UPDATE budget_daily_plan.campaign_id = budget_daily_plan.campaign_id;

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

/*
 * 피드 시험 캠페인 9101~9200 의 그날 후보다(#611).
 * 00:00 적재가 만드는 값과 같도록 캠페인, 가게, 메뉴 행에서 옮긴다. 일시 중단 캠페인은 PAUSED 후보로 둔다.
 * 생성 시각은 9001~9016 뒤로 1분씩 늦춘다.
 */
INSERT INTO ad_candidate (business_date, campaign_id, store_id, store_name, store_image_url, category_code,
                          latitude, longitude, target_radius, target_gender, target_age_groups,
                          discount_target_type, discount_type, discount_value, discount_menu_name,
                          usable_start_time, usable_end_time, issue_quantity, campaign_created_at,
                          new_priority_until, campaign_state, state_changed_at, sold_out_at, created_at, updated_at)
SELECT '${seedBusinessDate}', c.campaign_id, s.store_id, s.name, NULL, s.category_code, s.latitude, s.longitude,
       c.target_radius, c.target_gender, c.target_age_groups, c.discount_target_type, c.discount_type,
       c.discount_value, m.name, c.usable_start_time, c.usable_end_time, c.issue_quantity,
       NOW(6) + INTERVAL (c.campaign_id - 9080) MINUTE, NULL, IF(c.status = 'PAUSED', 'PAUSED', 'SERVABLE'),
       NULL, NULL, NOW(6), NOW(6)
FROM campaign c
JOIN store s ON s.store_id = c.store_id
LEFT JOIN store_menu m ON m.store_menu_id = c.target_menu_id
WHERE c.campaign_id BETWEEN 9101 AND 9200
ON DUPLICATE KEY UPDATE ad_candidate.campaign_id = ad_candidate.campaign_id;

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

-- 피드 시험 캠페인 9101~9200 가운데 그날 서빙되는 캠페인의 쿠폰 이벤트다(#611). 예산 계획이 없거나 일시 중단이면 만들지 않는다
INSERT INTO coupon_event (campaign_id, business_date, issue_quantity, usable_start_at, usable_end_at, status,
                          store_id, store_name, store_image_url, discount_target_type, discount_type, discount_value,
                          target_menu_id, target_menu_name, target_menu_price, target_menu_discounted_price,
                          created_at, updated_at)
SELECT c.campaign_id, c.business_date, c.issue_quantity, TIMESTAMP(c.business_date, c.usable_start_time),
       TIMESTAMP(c.business_date, c.usable_end_time), 'ACTIVE', c.store_id, c.store_name, NULL,
       c.discount_target_type, c.discount_type, c.discount_value, m.store_menu_id, m.name, m.price,
       CASE WHEN m.store_menu_id IS NULL THEN NULL
            WHEN c.discount_type = 'PERCENT' THEN m.price * (100 - c.discount_value) / 100
            ELSE m.price - c.discount_value END,
       NOW(6), NOW(6)
FROM ad_candidate c
JOIN campaign p ON p.campaign_id = c.campaign_id
JOIN budget_daily_plan b ON b.campaign_id = c.campaign_id AND b.business_date = c.business_date
LEFT JOIN store_menu m ON m.store_menu_id = p.target_menu_id
WHERE c.business_date = '${seedBusinessDate}' AND c.campaign_id BETWEEN 9101 AND 9200 AND c.campaign_state = 'SERVABLE'
ON DUPLICATE KEY UPDATE coupon_event.coupon_event_id = coupon_event.coupon_event_id;

/*
 * 쿠폰 재고다.
 * 이벤트마다 1번부터 발급 수량까지 만든다.
 * 코드는 이벤트와 번호로 정해져 다시 돌아도 같다.
 */
INSERT INTO coupon_inventory (coupon_event_id, sequence_no, coupon_code, status, usable_start_at, usable_end_at,
                              created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 50)
SELECT ce.coupon_event_id, seq.n, UNHEX(MD5(CONCAT('seed-', ce.coupon_event_id, '-', seq.n))), 'AVAILABLE',
       ce.usable_start_at, ce.usable_end_at, NOW(6), NOW(6)
FROM coupon_event ce
CROSS JOIN seq
WHERE (ce.campaign_id IN (9001, 9002) OR ce.campaign_id BETWEEN 9101 AND 9200)
  AND ce.business_date = '${seedBusinessDate}' AND seq.n <= ce.issue_quantity
ON DUPLICATE KEY UPDATE coupon_inventory_id = coupon_inventory_id;

/*
 * 지난 6일의 후보다(#436).
 * 오늘 후보를 날짜만 바꿔 복사한다. 아래 로그가 그날 후보를 가리킨다.
 * 피드 시험 캠페인 9101~9200 은 로그가 없어 복사하지 않는다(#611).
 */
INSERT INTO ad_candidate (business_date, campaign_id, store_id, store_name, store_image_url, category_code,
                          latitude, longitude, target_radius, target_gender, target_age_groups,
                          discount_target_type, discount_type, discount_value, discount_menu_name,
                          usable_start_time, usable_end_time, issue_quantity, campaign_created_at,
                          new_priority_until, campaign_state, state_changed_at, sold_out_at, created_at, updated_at)
WITH RECURSIVE ago (d) AS (SELECT 1 UNION ALL SELECT d + 1 FROM ago WHERE d < 6)
SELECT DATE_SUB(c.business_date, INTERVAL ago.d DAY), c.campaign_id, c.store_id, c.store_name, c.store_image_url,
       c.category_code, c.latitude, c.longitude, c.target_radius, c.target_gender, c.target_age_groups,
       c.discount_target_type, c.discount_type, c.discount_value, c.discount_menu_name,
       c.usable_start_time, c.usable_end_time, c.issue_quantity, c.campaign_created_at,
       c.new_priority_until, c.campaign_state, c.state_changed_at, c.sold_out_at, NOW(6), NOW(6)
FROM ad_candidate c
CROSS JOIN ago
WHERE c.business_date = '${seedBusinessDate}' AND c.campaign_id NOT BETWEEN 9101 AND 9200
ON DUPLICATE KEY UPDATE ad_candidate.campaign_id = ad_candidate.campaign_id;

/*
 * 회원 9001~9010 이 지난 영업일에 피드를 한 번씩 받은 카드 10장이다.
 * 회원 m 의 영업일은 MOD(m, 6) + 1 일 전이다. 자리 1, 4, 7 이 배분 슬롯이고 캠페인은 9001~9012 를 돌아가며 고른다.
 * 정산 시드(#442)가 같은 노출의 판정 기록을 만들 수 있게 serve_id 를 규칙으로 정한다. 규칙을 바꾸면 #442 에 알린다.
 */
INSERT INTO serve_log (serve_id, business_date, feed_id, member_id, campaign_id, store_id, slot_type, slot_position,
                       served_at, created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10),
               feed (m, d) AS (SELECT 9000 + n, DATE_SUB('${seedBusinessDate}', INTERVAL MOD(9000 + n, 6) + 1 DAY) FROM seq)
SELECT UNHEX(MD5(CONCAT('seed-serve-', feed.m, '-', feed.d, '-', seq.n))), feed.d,
       UNHEX(MD5(CONCAT('seed-feed-', feed.m, '-', feed.d))), feed.m,
       9001 + MOD(feed.m + seq.n, 12), 9001 + MOD(feed.m + seq.n, 12),
       IF(seq.n IN (1, 4, 7), 'ALLOCATION', 'RELEVANCE'), seq.n,
       TIMESTAMP(feed.d, '10:30:00') + INTERVAL seq.n SECOND, NOW(6), NOW(6)
FROM feed
JOIN seq ON seq.n BETWEEN 1 AND 10
ON DUPLICATE KEY UPDATE serve_log.serve_id = serve_log.serve_id;

/*
 * 위 카드마다 노출 한 건이다.
 * 자리 1~9 는 유효, 자리 10 은 serve 기록이 만료된 무효다. 모두 소진 전이라 정산이 판정할 노출이다.
 */
INSERT INTO impression_log (serve_id, member_id, business_date, campaign_id, displayed_at, lat_rounded, lng_rounded,
                            validity, invalid_reason, sold_out, created_at, updated_at)
SELECT s.serve_id, s.member_id, s.business_date, s.campaign_id, s.served_at + INTERVAL 1 SECOND, 37.498, 127.028,
       IF(s.slot_position <= 9, 'VALID', 'INVALID'), IF(s.slot_position <= 9, NULL, 'EXPIRED_OR_UNKNOWN'), FALSE,
       NOW(6), NOW(6)
FROM serve_log s
WHERE s.member_id BETWEEN 9001 AND 9999
ON DUPLICATE KEY UPDATE impression_log.serve_id = impression_log.serve_id;

/*
 * 위 카드마다 스와이프 한 건이다.
 * 자리 1, 4, 7, 10 은 찜이고 나머지는 패스다. 3의 배수 회원은 빈 찜 목록을 보려고 모두 패스한다.
 */
INSERT INTO swipe_log (serve_id, member_id, business_date, campaign_id, category_code, action, occurred_at,
                       created_at, updated_at)
SELECT s.serve_id, s.member_id, s.business_date, s.campaign_id, c.category_code,
       IF(MOD(s.slot_position, 3) = 1 AND MOD(s.member_id, 3) <> 0, 'WISH', 'PASS'),
       s.served_at + INTERVAL 2 SECOND, NOW(6), NOW(6)
FROM serve_log s
JOIN ad_candidate c ON c.business_date = s.business_date AND c.campaign_id = s.campaign_id
WHERE s.member_id BETWEEN 9001 AND 9999
ON DUPLICATE KEY UPDATE swipe_log.serve_id = swipe_log.serve_id;

-- 피드에서 찜한 스와이프마다 찜 목록에 하나다. 같은 캠페인을 다시 찜하면 처음 찜이 남는다
INSERT INTO wishlist (member_id, campaign_id, store_id, serve_id, created_at, updated_at)
SELECT w.member_id, w.campaign_id, w.campaign_id, w.serve_id, w.occurred_at, w.occurred_at
FROM swipe_log w
WHERE w.member_id BETWEEN 9001 AND 9999 AND w.action = 'WISH'
ON DUPLICATE KEY UPDATE wishlist.wishlist_id = wishlist.wishlist_id;

/*
 * 카카오로 가입해 온보딩한 회원의 찜이다. 시험 회원은 로그인할 수 없어 HTTP 로 찜 목록을 받으려면 이 행이 있어야 한다.
 * 홀수 회원만 오늘 후보 셋(전체 할인 9001, 메뉴 할인 9002, 9010)을 받고 짝수 회원은 빈 찜 목록을 본다.
 * 날마다 다시 돌아 그사이 가입한 회원도 다음 날부터 받는다. 지운 찜도 다음 날 다시 들어간다.
 */
INSERT INTO wishlist (member_id, campaign_id, store_id, serve_id, created_at, updated_at)
SELECT mp.member_id, pick.campaign_id, pick.campaign_id, NULL,
       NOW(6) - INTERVAL pick.minutes MINUTE, NOW(6) - INTERVAL pick.minutes MINUTE
FROM member_profile mp
JOIN member m ON m.member_id = mp.member_id
CROSS JOIN (SELECT 9001 AS campaign_id, 30 AS minutes
            UNION ALL SELECT 9002, 20
            UNION ALL SELECT 9010, 10) pick
WHERE mp.onboarding_completed_at IS NOT NULL
  AND m.status = 'ACTIVE'
  AND m.member_id NOT BETWEEN 9000 AND 9999
  AND MOD(m.member_id, 2) = 1
ON DUPLICATE KEY UPDATE wishlist.wishlist_id = wishlist.wishlist_id;

-- 시험 회원 50명의 어제와 그제 첫 접속 위치다
INSERT INTO feed_visit_daily (business_date, member_id, lat_rounded, lng_rounded, created_at, updated_at)
WITH RECURSIVE ago (d) AS (SELECT 1 UNION ALL SELECT d + 1 FROM ago WHERE d < 2)
SELECT DATE_SUB('${seedBusinessDate}', INTERVAL ago.d DAY), mp.member_id, ROUND(mp.latitude, 3),
       ROUND(mp.longitude, 3), TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL ago.d DAY), '10:30:00'), NOW(6)
FROM member_profile mp
CROSS JOIN ago
WHERE mp.member_id BETWEEN 9001 AND 9050
ON DUPLICATE KEY UPDATE feed_visit_daily.member_id = feed_visit_daily.member_id;
