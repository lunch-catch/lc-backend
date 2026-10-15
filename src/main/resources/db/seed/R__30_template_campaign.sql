/*
 * 게시된 템플릿 하나와 가게마다 진행 중(ACTIVE) 캠페인과 포스터를 둔다.
 * 캠페인 기간은 넉넉히 잡는다.
 * 날짜에 묶인 행(쿠폰 이벤트, 광고 후보, 예산)은 R__50 이 오늘 날짜로 넣는다.
 */
INSERT INTO template (template_id, name, status, html_content, active, published_at, published_version_number,
                      activated_by, activated_at, last_modified_by, last_modified_at, created_at, updated_at)
VALUES (9001, '시드 기본 템플릿', 'PUBLISHED',
        '<div class="poster" style="background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><span data-slot="adLabel">광고</span><h1 data-slot="eventName">이벤트명</h1><p data-slot="discount">할인 내용</p><p data-slot="period">기간</p><img data-slot="image" alt="메뉴 이미지"></div>',
        1, NOW(6), 1, 9001, NOW(6), 9001, NOW(6), NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE template_id = template_id;

INSERT INTO template_version (template_version_id, template_id, version_number, request_prompt, request_id,
                              html_content, created_at, updated_at)
VALUES (9001, 9001, 1, '따뜻한 주황색 점심 할인 포스터', 'seed-template-9001',
        '<div class="poster" style="background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><span data-slot="adLabel">광고</span><h1 data-slot="eventName">이벤트명</h1><p data-slot="discount">할인 내용</p><p data-slot="period">기간</p><img data-slot="image" alt="메뉴 이미지"></div>',
        NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE template_version_id = template_version_id;

/*
 * 템플릿 개수 상한(10)을 세는 행이다.
 * 시드 템플릿을 넣었으니 실제 개수에 맞춘다.
 */
UPDATE template_quota SET current_count = (SELECT COUNT(*) FROM template), updated_at = NOW(6);

-- 9001 은 주문 전체 2,000원 할인이고 9002 는 떡볶이 20% 할인이다
INSERT INTO campaign (campaign_id, owner_id, store_id, status, status_changed_at, discount_target_type, discount_type,
                      discount_value, target_menu_id, issue_quantity, usable_start_time, usable_end_time,
                      min_order_amount, notice, target_radius, target_gender, target_age_groups, daily_budget,
                      start_date, end_date, created_at, updated_at)
VALUES (9001, 9001, 9001, 'ACTIVE', NOW(6), 'ALL', 'AMOUNT', 2000, NULL, 50, '11:00', '14:00',
        10000, '시드 캠페인입니다', 1000, 'ALL', 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 10000,
        '2026-10-01', '2027-12-31', NOW(6), NOW(6)),
       (9002, 9002, 9002, 'ACTIVE', NOW(6), 'MENU', 'PERCENT', 20, 9004, 50, '11:00', '14:00',
        NULL, '시드 캠페인입니다', 1000, 'ALL', 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 10000,
        '2026-10-01', '2027-12-31', NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE campaign_id = campaign_id;

INSERT INTO poster (poster_id, campaign_id, template_id, title, slot_values, html_content, image_object_key,
                    created_at, updated_at)
VALUES (9001, 9001, 9001, '런치 한식당 점심 할인',
        JSON_OBJECT('adLabel', JSON_OBJECT('type', 'TEXT', 'value', '광고'),
                    'eventName', JSON_OBJECT('type', 'TEXT', 'value', '런치 한식당 점심 할인'),
                    'discount', JSON_OBJECT('type', 'TEXT', 'value', '전 메뉴 2,000원 할인'),
                    'period', JSON_OBJECT('type', 'TEXT', 'value', '매일 11:00 ~ 14:00'),
                    'image', JSON_OBJECT('type', 'IMAGE', 'value', 'seed/poster-9001.jpg')),
        '<div class="poster" style="background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><span data-slot="adLabel">광고</span><h1 data-slot="eventName">런치 한식당 점심 할인</h1><p data-slot="discount">전 메뉴 2,000원 할인</p><p data-slot="period">매일 11:00 ~ 14:00</p><img data-slot="image" alt="메뉴 이미지"></div>',
        'seed/poster-9001.jpg', NOW(6), NOW(6)),
       (9002, 9002, 9001, '런치 분식 떡볶이 할인',
        JSON_OBJECT('adLabel', JSON_OBJECT('type', 'TEXT', 'value', '광고'),
                    'eventName', JSON_OBJECT('type', 'TEXT', 'value', '런치 분식 떡볶이 할인'),
                    'discount', JSON_OBJECT('type', 'TEXT', 'value', '떡볶이 20% 할인'),
                    'period', JSON_OBJECT('type', 'TEXT', 'value', '매일 11:00 ~ 14:00'),
                    'image', JSON_OBJECT('type', 'IMAGE', 'value', 'seed/poster-9002.jpg')),
        '<div class="poster" style="background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><span data-slot="adLabel">광고</span><h1 data-slot="eventName">런치 분식 떡볶이 할인</h1><p data-slot="discount">떡볶이 20% 할인</p><p data-slot="period">매일 11:00 ~ 14:00</p><img data-slot="image" alt="메뉴 이미지"></div>',
        'seed/poster-9002.jpg', NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE poster_id = poster_id;
