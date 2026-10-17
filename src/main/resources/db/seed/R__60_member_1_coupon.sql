/*
 * member_id = 1인 기존 회원의 쿠폰 API를 시험한다.
 * 9901은 오늘 사용할 미사용 쿠폰이고 9902, 9903, 9904는 각각 사용 완료, 만료, 삭제 쿠폰이다.
 * 발급일은 각각 1일 전, 2일 전, 7일 전, 30일 전으로 나누고 오늘 신규 발급 한도를 남긴다.
 * 고정 ID를 사용하므로 다시 실행해도 기존 쿠폰의 상태와 날짜는 바뀌지 않는다.
 */
INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity, usable_start_at,
                          usable_end_at, status, store_id, store_name, store_image_url,
                          discount_target_type, discount_type, discount_value,
                          target_menu_id, target_menu_name, target_menu_price, target_menu_discounted_price,
                          created_at, updated_at)
VALUES (9901, 9004, DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), 1,
        TIMESTAMP('${seedBusinessDate}'), TIMESTAMP(DATE_ADD('${seedBusinessDate}', INTERVAL 1 DAY)),
        'ENDED', 9004, '런치 짜장', NULL, 'ALL', 'AMOUNT', 3000,
        NULL, NULL, NULL, NULL, NOW(6), NOW(6)),
       (9902, 9007, DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), 1,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '11:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '14:00:00'),
        'ENDED', 9007, '런치 쌀국수', NULL, 'ALL', 'AMOUNT', 1500,
        NULL, NULL, NULL, NULL, NOW(6), NOW(6)),
       (9903, 9009, DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), 1,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), '11:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), '14:00:00'),
        'ENDED', 9009, '런치 국밥', NULL, 'ALL', 'AMOUNT', 2500,
        NULL, NULL, NULL, NULL, NOW(6), NOW(6)),
       (9904, 9012, DATE_SUB('${seedBusinessDate}', INTERVAL 30 DAY), 1,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 30 DAY), '11:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 30 DAY), '14:00:00'),
        'ENDED', 9012, '런치 스테이크', NULL, 'ALL', 'AMOUNT', 4000,
        NULL, NULL, NULL, NULL, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE coupon_event_id = coupon_event_id;

-- coupon_code는 재고와 회원 쿠폰에 같은 16바이트 값을 넣는다.
INSERT INTO coupon_inventory (coupon_inventory_id, coupon_event_id, sequence_no, coupon_code, status,
                              usable_start_at, usable_end_at, created_at, updated_at)
VALUES (9901, 9901, 1, UNHEX('00000000000000000000000000009901'), 'ISSUED',
        TIMESTAMP('${seedBusinessDate}'), TIMESTAMP(DATE_ADD('${seedBusinessDate}', INTERVAL 1 DAY)), NOW(6), NOW(6)),
       (9902, 9902, 1, UNHEX('00000000000000000000000000009902'), 'ISSUED',
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '11:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '14:00:00'), NOW(6), NOW(6)),
       (9903, 9903, 1, UNHEX('00000000000000000000000000009903'), 'ISSUED',
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), '11:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), '14:00:00'), NOW(6), NOW(6)),
       (9904, 9904, 1, UNHEX('00000000000000000000000000009904'), 'ISSUED',
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 30 DAY), '11:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 30 DAY), '14:00:00'), NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE coupon_inventory_id = coupon_inventory_id;

-- memberCouponId 9901로 상세 조회, QR 생성, 삭제를 시험하며 QR 토큰은 생성 API가 넣는다.
INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code, status,
                           usable_start_at, usable_end_at, created_at, updated_at)
VALUES (9901, 9901, 1, UNHEX('00000000000000000000000000009901'), 'ISSUED',
        TIMESTAMP('${seedBusinessDate}'), TIMESTAMP(DATE_ADD('${seedBusinessDate}', INTERVAL 1 DAY)),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), '11:00:00'), NOW(6)),
       (9902, 9902, 1, UNHEX('00000000000000000000000000009902'), 'USED',
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '11:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '14:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '11:00:00'), NOW(6)),
       (9903, 9903, 1, UNHEX('00000000000000000000000000009903'), 'EXPIRED',
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), '11:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), '14:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), '11:00:00'), NOW(6)),
       (9904, 9904, 1, UNHEX('00000000000000000000000000009904'), 'REMOVED',
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 30 DAY), '11:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 30 DAY), '14:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 30 DAY), '11:00:00'), NOW(6))
ON DUPLICATE KEY UPDATE member_coupon_id = member_coupon_id;

-- 쿠폰 요약은 기존 이력이 없다면 누적 발급 4장, 사용 1장, 할인액 1500원을 반환한다.
INSERT INTO coupon_usage_history (coupon_usage_history_id, member_coupon_id, store_id, discount_amount,
                                  created_at, updated_at)
VALUES (9901, 9902, 9007, 1500,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '11:30:00'), NOW(6))
ON DUPLICATE KEY UPDATE coupon_usage_history_id = coupon_usage_history_id;

-- 발급일마다 한 장을 기록하며 30일 전에 발급한 삭제 쿠폰의 횟수는 0으로 기록한다.
INSERT INTO coupon_daily_limit (coupon_daily_limit_id, member_id, issued_count, business_date, created_at, updated_at)
VALUES (9901, 1, 1, DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), NOW(6), NOW(6)),
       (9902, 1, 1, DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), NOW(6), NOW(6)),
       (9903, 1, 1, DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), NOW(6), NOW(6)),
       (9904, 1, 0, DATE_SUB('${seedBusinessDate}', INTERVAL 30 DAY), NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE coupon_daily_limit_id = coupon_daily_limit_id;

/*
 * QR 사용은 owner4@seed.lunchcatch.com으로 로그인하고 latitude=37.4954, longitude=127.0276으로 요청한다.
 * 사용 이력 조회는 owner7@seed.lunchcatch.com으로 로그인하고 storeId=9007, from과 to는 2일 전 날짜로 요청한다.
 * 신규 발급 eventId는 POST /v1/coupon-events:getStatus에 storeIds=[9001,9002]를 보내 확인한다.
 * 신규 발급은 한국 시간 11:00 이상 14:00 미만에만 가능하다.
 */
