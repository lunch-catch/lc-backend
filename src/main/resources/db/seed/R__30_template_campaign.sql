/*
 * 게시된 템플릿 하나와 가게마다 진행 중(ACTIVE) 캠페인과 포스터를 둔다.
 * 캠페인 기간은 넉넉히 잡는다.
 * 날짜에 묶인 행(쿠폰 이벤트, 광고 후보, 예산)은 R__50 이 오늘 날짜로 넣는다.
 */
INSERT INTO template (template_id, name, status, html_content, active, published_at, published_version_number,
                      activated_by, activated_at, last_modified_by, last_modified_at, created_at, updated_at)
VALUES (9001, '시드 기본 템플릿', 'PUBLISHED',
        '<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><h1 data-slot="eventName">이벤트명</h1><p data-slot="discount">할인 내용</p><p data-slot="period">기간</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>',
        1, NOW(6), 1, 9001, NOW(6), 9001, NOW(6), NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE template_id = template_id;

INSERT INTO template_version (template_version_id, template_id, version_number, request_prompt, request_id,
                              html_content, created_at, updated_at)
VALUES (9001, 9001, 1, '따뜻한 주황색 점심 할인 포스터', 'seed-template-9001',
        '<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><h1 data-slot="eventName">이벤트명</h1><p data-slot="discount">할인 내용</p><p data-slot="period">기간</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>',
        NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE template_version_id = template_version_id;

/*
 * 템플릿 개수 상한(10)을 세는 행이다.
 * 시드 템플릿을 넣었으니 실제 개수에 맞춘다.
 */
UPDATE template_quota SET current_count = (SELECT COUNT(*) FROM template), updated_at = NOW(6);

-- 9001 은 주문 전체 2,000원 할인이고 9002 는 떡볶이 20% 할인이다. 9003~9016 은 피드 시험용이다(R__50)
INSERT INTO campaign (campaign_id, owner_id, store_id, status, status_changed_at, discount_target_type, discount_type,
                      discount_value, target_menu_id, issue_quantity, usable_start_time, usable_end_time,
                      min_order_amount, notice, target_radius, target_gender, target_age_groups, daily_budget,
                      start_date, end_date, created_at, updated_at)
VALUES (9001, 9001, 9001, 'ACTIVE', NOW(6), 'ALL', 'AMOUNT', 2000, NULL, 50, '11:00', '14:00',
        10000, '시드 캠페인입니다', 1000, 'ALL', 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 10000,
        '2026-10-01', '2027-12-31', NOW(6), NOW(6)),
       (9002, 9002, 9002, 'ACTIVE', NOW(6), 'MENU', 'PERCENT', 20, 9004, 50, '11:00', '14:00',
        NULL, '시드 캠페인입니다', 1000, 'ALL', 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 10000,
        '2026-10-01', '2027-12-31', NOW(6), NOW(6)),
       (9003, 9003, 9003, 'ACTIVE', NOW(6), 'ALL', 'PERCENT', 15, NULL, 50, '11:00', '14:00',
        NULL, '시드 캠페인입니다', 1000, 'ALL', 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 10000, '2026-10-01', '2027-12-31', NOW(6), NOW(6)),
       (9004, 9004, 9004, 'ACTIVE', NOW(6), 'ALL', 'AMOUNT', 3000, NULL, 50, '11:00', '14:00',
        NULL, '시드 캠페인입니다', 1000, 'ALL', 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 10000, '2026-10-01', '2027-12-31', NOW(6), NOW(6)),
       (9005, 9005, 9005, 'ACTIVE', NOW(6), 'ALL', 'PERCENT', 10, NULL, 50, '11:00', '14:00',
        NULL, '시드 캠페인입니다', 1000, 'ALL', 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 10000, '2026-10-01', '2027-12-31', NOW(6), NOW(6)),
       (9006, 9006, 9006, 'ACTIVE', NOW(6), 'ALL', 'PERCENT', 30, NULL, 50, '11:00', '14:00',
        NULL, '시드 캠페인입니다', 1000, 'ALL', 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 10000, '2026-10-01', '2027-12-31', NOW(6), NOW(6)),
       (9007, 9007, 9007, 'ACTIVE', NOW(6), 'ALL', 'AMOUNT', 1500, NULL, 50, '11:00', '14:00',
        NULL, '시드 캠페인입니다', 1000, 'ALL', 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 10000, '2026-10-01', '2027-12-31', NOW(6), NOW(6)),
       (9008, 9008, 9008, 'ACTIVE', NOW(6), 'ALL', 'PERCENT', 25, NULL, 50, '11:00', '14:00',
        NULL, '시드 캠페인입니다', 1000, 'ALL', 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 10000, '2026-10-01', '2027-12-31', NOW(6), NOW(6)),
       (9009, 9009, 9009, 'ACTIVE', NOW(6), 'ALL', 'AMOUNT', 2500, NULL, 50, '11:00', '14:00',
        NULL, '시드 캠페인입니다', 2000, 'ALL', 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 10000, '2026-10-01', '2027-12-31', NOW(6), NOW(6)),
       (9010, 9010, 9010, 'ACTIVE', NOW(6), 'MENU', 'PERCENT', 20, 9110, 50, '11:00', '14:00',
        NULL, '시드 캠페인입니다', 2000, 'ALL', 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 10000, '2026-10-01', '2027-12-31', NOW(6), NOW(6)),
       (9011, 9011, 9011, 'ACTIVE', NOW(6), 'ALL', 'PERCENT', 15, NULL, 50, '11:00', '14:00',
        NULL, '시드 캠페인입니다', 2000, 'ALL', 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 10000, '2026-10-01', '2027-12-31', NOW(6), NOW(6)),
       (9012, 9012, 9012, 'ACTIVE', NOW(6), 'ALL', 'AMOUNT', 4000, NULL, 50, '11:00', '14:00',
        NULL, '시드 캠페인입니다', 2000, 'ALL', 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 10000, '2026-10-01', '2027-12-31', NOW(6), NOW(6)),
       (9013, 9013, 9013, 'ACTIVE', NOW(6), 'ALL', 'AMOUNT', 1000, NULL, 50, '11:00', '14:00',
        NULL, '시드 캠페인입니다', 500, 'ALL', 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 10000, '2026-10-01', '2027-12-31', NOW(6), NOW(6)),
       (9014, 9014, 9014, 'ACTIVE', NOW(6), 'ALL', 'AMOUNT', 1000, NULL, 50, '11:00', '14:00',
        NULL, '시드 캠페인입니다', 1000, 'MALE', 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 10000, '2026-10-01', '2027-12-31', NOW(6), NOW(6)),
       (9015, 9015, 9015, 'ACTIVE', NOW(6), 'ALL', 'PERCENT', 10, NULL, 50, '11:00', '14:00',
        NULL, '시드 캠페인입니다', 1000, 'ALL', 'AGE_20S', 10000, '2026-10-01', '2027-12-31', NOW(6), NOW(6)),
       (9016, 9016, 9016, 'ACTIVE', NOW(6), 'ALL', 'AMOUNT', 1000, NULL, 50, '11:00', '14:00',
        NULL, '시드 캠페인입니다', 1000, 'ALL', 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS', 10000, '2026-10-01', '2027-12-31', NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE campaign_id = campaign_id;

INSERT INTO poster (poster_id, campaign_id, template_id, title, slot_values, html_content, image_object_key,
                    created_at, updated_at)
VALUES (9001, 9001, 9001, '런치 한식당 점심 할인',
        JSON_OBJECT('eventName', JSON_OBJECT('type', 'TEXT', 'value', '런치 한식당 점심 할인'),
                    'discount', JSON_OBJECT('type', 'TEXT', 'value', '전 메뉴 2,000원 할인'),
                    'period', JSON_OBJECT('type', 'TEXT', 'value', '매일 11:00 ~ 14:00'),
                    'image', JSON_OBJECT('type', 'IMAGE', 'value', 'seed/poster-9001.jpg')),
        '<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><h1 data-slot="eventName">런치 한식당 점심 할인</h1><p data-slot="discount">전 메뉴 2,000원 할인</p><p data-slot="period">매일 11:00 ~ 14:00</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>',
        'seed/poster-9001.jpg', NOW(6), NOW(6)),
       (9002, 9002, 9001, '런치 분식 떡볶이 할인',
        JSON_OBJECT('eventName', JSON_OBJECT('type', 'TEXT', 'value', '런치 분식 떡볶이 할인'),
                    'discount', JSON_OBJECT('type', 'TEXT', 'value', '떡볶이 20% 할인'),
                    'period', JSON_OBJECT('type', 'TEXT', 'value', '매일 11:00 ~ 14:00'),
                    'image', JSON_OBJECT('type', 'IMAGE', 'value', 'seed/poster-9002.jpg')),
        '<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><h1 data-slot="eventName">런치 분식 떡볶이 할인</h1><p data-slot="discount">떡볶이 20% 할인</p><p data-slot="period">매일 11:00 ~ 14:00</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>',
        'seed/poster-9002.jpg', NOW(6), NOW(6)),
       (9003, 9003, 9001, '런치 스시 점심 할인',
        JSON_OBJECT('eventName', JSON_OBJECT('type', 'TEXT', 'value', '런치 스시 점심 할인'),
                    'discount', JSON_OBJECT('type', 'TEXT', 'value', '전 메뉴 15% 할인'),
                    'period', JSON_OBJECT('type', 'TEXT', 'value', '매일 11:00 ~ 14:00'),
                    'image', JSON_OBJECT('type', 'IMAGE', 'value', 'seed/poster-9003.jpg')),
        '<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><h1 data-slot="eventName">런치 스시 점심 할인</h1><p data-slot="discount">전 메뉴 15% 할인</p><p data-slot="period">매일 11:00 ~ 14:00</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>',
        'seed/poster-9003.jpg', NOW(6), NOW(6)),
       (9004, 9004, 9001, '런치 짜장 점심 할인',
        JSON_OBJECT('eventName', JSON_OBJECT('type', 'TEXT', 'value', '런치 짜장 점심 할인'),
                    'discount', JSON_OBJECT('type', 'TEXT', 'value', '전 메뉴 3,000원 할인'),
                    'period', JSON_OBJECT('type', 'TEXT', 'value', '매일 11:00 ~ 14:00'),
                    'image', JSON_OBJECT('type', 'IMAGE', 'value', 'seed/poster-9004.jpg')),
        '<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><h1 data-slot="eventName">런치 짜장 점심 할인</h1><p data-slot="discount">전 메뉴 3,000원 할인</p><p data-slot="period">매일 11:00 ~ 14:00</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>',
        'seed/poster-9004.jpg', NOW(6), NOW(6)),
       (9005, 9005, 9001, '런치 파스타 점심 할인',
        JSON_OBJECT('eventName', JSON_OBJECT('type', 'TEXT', 'value', '런치 파스타 점심 할인'),
                    'discount', JSON_OBJECT('type', 'TEXT', 'value', '전 메뉴 10% 할인'),
                    'period', JSON_OBJECT('type', 'TEXT', 'value', '매일 11:00 ~ 14:00'),
                    'image', JSON_OBJECT('type', 'IMAGE', 'value', 'seed/poster-9005.jpg')),
        '<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><h1 data-slot="eventName">런치 파스타 점심 할인</h1><p data-slot="discount">전 메뉴 10% 할인</p><p data-slot="period">매일 11:00 ~ 14:00</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>',
        'seed/poster-9005.jpg', NOW(6), NOW(6)),
       (9006, 9006, 9001, '런치 카페 점심 할인',
        JSON_OBJECT('eventName', JSON_OBJECT('type', 'TEXT', 'value', '런치 카페 점심 할인'),
                    'discount', JSON_OBJECT('type', 'TEXT', 'value', '전 메뉴 30% 할인'),
                    'period', JSON_OBJECT('type', 'TEXT', 'value', '매일 11:00 ~ 14:00'),
                    'image', JSON_OBJECT('type', 'IMAGE', 'value', 'seed/poster-9006.jpg')),
        '<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><h1 data-slot="eventName">런치 카페 점심 할인</h1><p data-slot="discount">전 메뉴 30% 할인</p><p data-slot="period">매일 11:00 ~ 14:00</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>',
        'seed/poster-9006.jpg', NOW(6), NOW(6)),
       (9007, 9007, 9001, '런치 쌀국수 점심 할인',
        JSON_OBJECT('eventName', JSON_OBJECT('type', 'TEXT', 'value', '런치 쌀국수 점심 할인'),
                    'discount', JSON_OBJECT('type', 'TEXT', 'value', '전 메뉴 1,500원 할인'),
                    'period', JSON_OBJECT('type', 'TEXT', 'value', '매일 11:00 ~ 14:00'),
                    'image', JSON_OBJECT('type', 'IMAGE', 'value', 'seed/poster-9007.jpg')),
        '<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><h1 data-slot="eventName">런치 쌀국수 점심 할인</h1><p data-slot="discount">전 메뉴 1,500원 할인</p><p data-slot="period">매일 11:00 ~ 14:00</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>',
        'seed/poster-9007.jpg', NOW(6), NOW(6)),
       (9008, 9008, 9001, '런치 버거 점심 할인',
        JSON_OBJECT('eventName', JSON_OBJECT('type', 'TEXT', 'value', '런치 버거 점심 할인'),
                    'discount', JSON_OBJECT('type', 'TEXT', 'value', '전 메뉴 25% 할인'),
                    'period', JSON_OBJECT('type', 'TEXT', 'value', '매일 11:00 ~ 14:00'),
                    'image', JSON_OBJECT('type', 'IMAGE', 'value', 'seed/poster-9008.jpg')),
        '<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><h1 data-slot="eventName">런치 버거 점심 할인</h1><p data-slot="discount">전 메뉴 25% 할인</p><p data-slot="period">매일 11:00 ~ 14:00</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>',
        'seed/poster-9008.jpg', NOW(6), NOW(6)),
       (9009, 9009, 9001, '런치 국밥 점심 할인',
        JSON_OBJECT('eventName', JSON_OBJECT('type', 'TEXT', 'value', '런치 국밥 점심 할인'),
                    'discount', JSON_OBJECT('type', 'TEXT', 'value', '전 메뉴 2,500원 할인'),
                    'period', JSON_OBJECT('type', 'TEXT', 'value', '매일 11:00 ~ 14:00'),
                    'image', JSON_OBJECT('type', 'IMAGE', 'value', 'seed/poster-9009.jpg')),
        '<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><h1 data-slot="eventName">런치 국밥 점심 할인</h1><p data-slot="discount">전 메뉴 2,500원 할인</p><p data-slot="period">매일 11:00 ~ 14:00</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>',
        'seed/poster-9009.jpg', NOW(6), NOW(6)),
       (9010, 9010, 9001, '런치 라멘 점심 할인',
        JSON_OBJECT('eventName', JSON_OBJECT('type', 'TEXT', 'value', '런치 라멘 점심 할인'),
                    'discount', JSON_OBJECT('type', 'TEXT', 'value', '돈코츠 라멘 20% 할인'),
                    'period', JSON_OBJECT('type', 'TEXT', 'value', '매일 11:00 ~ 14:00'),
                    'image', JSON_OBJECT('type', 'IMAGE', 'value', 'seed/poster-9010.jpg')),
        '<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><h1 data-slot="eventName">런치 라멘 점심 할인</h1><p data-slot="discount">돈코츠 라멘 20% 할인</p><p data-slot="period">매일 11:00 ~ 14:00</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>',
        'seed/poster-9010.jpg', NOW(6), NOW(6)),
       (9011, 9011, 9001, '런치 마라탕 점심 할인',
        JSON_OBJECT('eventName', JSON_OBJECT('type', 'TEXT', 'value', '런치 마라탕 점심 할인'),
                    'discount', JSON_OBJECT('type', 'TEXT', 'value', '전 메뉴 15% 할인'),
                    'period', JSON_OBJECT('type', 'TEXT', 'value', '매일 11:00 ~ 14:00'),
                    'image', JSON_OBJECT('type', 'IMAGE', 'value', 'seed/poster-9011.jpg')),
        '<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><h1 data-slot="eventName">런치 마라탕 점심 할인</h1><p data-slot="discount">전 메뉴 15% 할인</p><p data-slot="period">매일 11:00 ~ 14:00</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>',
        'seed/poster-9011.jpg', NOW(6), NOW(6)),
       (9012, 9012, 9001, '런치 스테이크 점심 할인',
        JSON_OBJECT('eventName', JSON_OBJECT('type', 'TEXT', 'value', '런치 스테이크 점심 할인'),
                    'discount', JSON_OBJECT('type', 'TEXT', 'value', '전 메뉴 4,000원 할인'),
                    'period', JSON_OBJECT('type', 'TEXT', 'value', '매일 11:00 ~ 14:00'),
                    'image', JSON_OBJECT('type', 'IMAGE', 'value', 'seed/poster-9012.jpg')),
        '<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><h1 data-slot="eventName">런치 스테이크 점심 할인</h1><p data-slot="discount">전 메뉴 4,000원 할인</p><p data-slot="period">매일 11:00 ~ 14:00</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>',
        'seed/poster-9012.jpg', NOW(6), NOW(6)),
       (9013, 9013, 9001, '런치 백반 점심 할인',
        JSON_OBJECT('eventName', JSON_OBJECT('type', 'TEXT', 'value', '런치 백반 점심 할인'),
                    'discount', JSON_OBJECT('type', 'TEXT', 'value', '전 메뉴 1,000원 할인'),
                    'period', JSON_OBJECT('type', 'TEXT', 'value', '매일 11:00 ~ 14:00'),
                    'image', JSON_OBJECT('type', 'IMAGE', 'value', 'seed/poster-9013.jpg')),
        '<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><h1 data-slot="eventName">런치 백반 점심 할인</h1><p data-slot="discount">전 메뉴 1,000원 할인</p><p data-slot="period">매일 11:00 ~ 14:00</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>',
        'seed/poster-9013.jpg', NOW(6), NOW(6)),
       (9014, 9014, 9001, '런치 순대 점심 할인',
        JSON_OBJECT('eventName', JSON_OBJECT('type', 'TEXT', 'value', '런치 순대 점심 할인'),
                    'discount', JSON_OBJECT('type', 'TEXT', 'value', '전 메뉴 1,000원 할인'),
                    'period', JSON_OBJECT('type', 'TEXT', 'value', '매일 11:00 ~ 14:00'),
                    'image', JSON_OBJECT('type', 'IMAGE', 'value', 'seed/poster-9014.jpg')),
        '<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><h1 data-slot="eventName">런치 순대 점심 할인</h1><p data-slot="discount">전 메뉴 1,000원 할인</p><p data-slot="period">매일 11:00 ~ 14:00</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>',
        'seed/poster-9014.jpg', NOW(6), NOW(6)),
       (9015, 9015, 9001, '런치 디저트 점심 할인',
        JSON_OBJECT('eventName', JSON_OBJECT('type', 'TEXT', 'value', '런치 디저트 점심 할인'),
                    'discount', JSON_OBJECT('type', 'TEXT', 'value', '전 메뉴 10% 할인'),
                    'period', JSON_OBJECT('type', 'TEXT', 'value', '매일 11:00 ~ 14:00'),
                    'image', JSON_OBJECT('type', 'IMAGE', 'value', 'seed/poster-9015.jpg')),
        '<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><h1 data-slot="eventName">런치 디저트 점심 할인</h1><p data-slot="discount">전 메뉴 10% 할인</p><p data-slot="period">매일 11:00 ~ 14:00</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>',
        'seed/poster-9015.jpg', NOW(6), NOW(6)),
       (9016, 9016, 9001, '런치 도시락 점심 할인',
        JSON_OBJECT('eventName', JSON_OBJECT('type', 'TEXT', 'value', '런치 도시락 점심 할인'),
                    'discount', JSON_OBJECT('type', 'TEXT', 'value', '전 메뉴 1,000원 할인'),
                    'period', JSON_OBJECT('type', 'TEXT', 'value', '매일 11:00 ~ 14:00'),
                    'image', JSON_OBJECT('type', 'IMAGE', 'value', 'seed/poster-9016.jpg')),
        '<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center"><h1 data-slot="eventName">런치 도시락 점심 할인</h1><p data-slot="discount">전 메뉴 1,000원 할인</p><p data-slot="period">매일 11:00 ~ 14:00</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>',
        'seed/poster-9016.jpg', NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE poster_id = poster_id;

/*
 * 피드 시험용 캠페인 9101~9200 이다(#611). 가게와 빠지는 경우의 배치는 R__20 의 주석에 있다.
 * 9111~9200 은 9111 부터 센 순번을 9로 나눈 나머지로 노출 대상 성별을 정한다. 0~4 는 전체, 5~6 은 남성, 7~8 은 여성이다.
 * 빠지는 경우 9101~9110 도 전체 대상이라 모두 합치면 전체 60, 남성 20, 여성 20 이다.
 * 9111~9200 의 전체 대상 50개 가운데 15개는 연령대 둘만, 성별 대상 40개의 절반은 연령대 하나만 받는다. 나머지는 전 연령대다.
 * 쿠폰 이벤트가 전체 할인은 금액만 받아(chk_coupon_event_discount_combination) 할인율은 메뉴 할인으로만 섞는다.
 * 사용 가능 시간은 점심, 저녁, 하루 종일을 섞고 여섯에 하나는 쿠폰 수량이 3 이라 소진을 볼 수 있다.
 */
INSERT INTO campaign (campaign_id, owner_id, store_id, status, status_changed_at, paused_reason, discount_target_type,
                      discount_type, discount_value, target_menu_id, issue_quantity, usable_start_time, usable_end_time,
                      min_order_amount, notice, target_radius, target_gender, target_age_groups, daily_budget,
                      start_date, end_date, created_at, updated_at)
WITH RECURSIVE seq (id) AS (SELECT 9101 UNION ALL SELECT id + 1 FROM seq WHERE id < 9200),
               n AS (SELECT id, id - 9101 AS k, id - 9111 AS j, IF(id < 9111, id - 9101, id - 9111) AS v FROM seq),
               p AS (SELECT id, k, j, v,
                            CASE WHEN k < 10 OR MOD(j, 9) < 5 THEN 'ALL' WHEN MOD(j, 9) < 7 THEN 'MALE' ELSE 'FEMALE' END AS gender,
                            CASE WHEN k < 10 THEN k
                                 WHEN MOD(j, 9) < 5 THEN FLOOR(j / 9) * 5 + MOD(j, 9)
                                 ELSE FLOOR(j / 9) * 4 + MOD(j, 9) - 5 END AS i,
                            MOD(v + IF(k < 10, 0, FLOOR(j / 9)), 3) AS discount
                     FROM n)
SELECT id, id, id, IF(k BETWEEN 7 AND 9, 'PAUSED', 'ACTIVE'), NOW(6), IF(k BETWEEN 7 AND 9, 'OWNER', NULL),
       IF(discount = 0, 'ALL', 'MENU'), IF(discount = 1, 'PERCENT', 'AMOUNT'),
       CASE discount WHEN 0 THEN 1000 + MOD(v, 5) * 500 WHEN 1 THEN 10 + MOD(v, 4) * 5 ELSE 1000 + MOD(v, 3) * 1000 END,
       IF(discount = 0, NULL, id + 200), IF(MOD(v, 6) = 0, 3, 50),
       ELT(MOD(v, 4) + 1, '11:00', '11:00', '17:00', '00:00'), ELT(MOD(v, 4) + 1, '14:00', '14:00', '21:00', '23:59'),
       NULL, '피드 시험 캠페인입니다',
       CASE WHEN k < 2 THEN 500 WHEN k < 10 THEN 1000 WHEN j < 45 THEN IF(MOD(j, 3) = 0, 3000, 2000) ELSE 3000 END,
       gender,
       CASE WHEN k >= 10 AND gender = 'ALL' AND MOD(i, 10) IN (0, 4, 8)
                THEN ELT(MOD(i, 4) + 1, 'AGE_20S,AGE_30S', 'AGE_30S,AGE_40S', 'AGE_40S,AGE_50_PLUS', 'AGE_20S,AGE_50_PLUS')
            WHEN k >= 10 AND gender <> 'ALL' AND MOD(FLOOR(j / 9), 2) = 1
                THEN ELT(MOD(i, 4) + 1, 'AGE_20S', 'AGE_30S', 'AGE_40S', 'AGE_50_PLUS')
            ELSE 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS' END,
       10000, '2026-10-01', '2027-12-31', NOW(6), NOW(6)
FROM p
ON DUPLICATE KEY UPDATE campaign_id = campaign_id;

INSERT INTO poster (poster_id, campaign_id, template_id, title, slot_values, html_content, image_object_key,
                    created_at, updated_at)
SELECT d.campaign_id, d.campaign_id, 9001, CONCAT(d.store_name, ' 할인'),
       JSON_OBJECT('eventName', JSON_OBJECT('type', 'TEXT', 'value', CONCAT(d.store_name, ' 할인')),
                   'discount', JSON_OBJECT('type', 'TEXT', 'value', d.discount_text),
                   'period', JSON_OBJECT('type', 'TEXT', 'value', d.period_text),
                   'image', JSON_OBJECT('type', 'IMAGE', 'value', CONCAT('seed/poster-', d.campaign_id, '.jpg'))),
       CONCAT('<div class="poster" style="display:flex;flex-direction:column;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;background:#FFF4E5;color:#4A2C00;padding:24px;text-align:center">',
              '<h1 data-slot="eventName">', d.store_name, ' 할인</h1>',
              '<p data-slot="discount">', d.discount_text, '</p><p data-slot="period">', d.period_text, '</p></div>'),
       CONCAT('seed/poster-', d.campaign_id, '.jpg'), NOW(6), NOW(6)
FROM (SELECT c.campaign_id, s.name AS store_name,
             CONCAT(IF(c.discount_target_type = 'ALL', '전 메뉴', m.name), ' ',
                    IF(c.discount_type = 'PERCENT', CONCAT(c.discount_value, '%'), CONCAT(FORMAT(c.discount_value, 0), '원')),
                    ' 할인') AS discount_text,
             CONCAT('매일 ', TIME_FORMAT(c.usable_start_time, '%H:%i'), ' ~ ', TIME_FORMAT(c.usable_end_time, '%H:%i')) AS period_text
      FROM campaign c
      JOIN store s ON s.store_id = c.store_id
      LEFT JOIN store_menu m ON m.store_menu_id = c.target_menu_id
      WHERE c.campaign_id BETWEEN 9101 AND 9200) d
ON DUPLICATE KEY UPDATE poster_id = poster_id;

/*
 * 시드 포스터 HTML 의 이미지에 주소를 넣는다. 피드가 내려주는 포스터 조회가 이 HTML 을 그대로 그린다.
 * 실제 포스터도 저장할 때 cdn.base-url 과 이미지 key 를 붙인 주소를 HTML 에 넣는다(PosterUploadService).
 * 위 INSERT 는 이미 있는 행을 건드리지 않아, 앞서 시드가 들어간 DB 도 맞추려고 따로 고친다.
 * 이미지 파일은 개발 버킷의 seed/ 아래에 있다. 로컬은 cdn.base-url 이 비어 있어 이미지가 보이지 않는다.
 * 대상은 위에서 넣은 시드 포스터(9001~9016, 9101~9200)뿐이다. 시드 뒤에 만든 포스터는 9201 부터 번호를 받아 건드리지 않는다.
 */
UPDATE poster
SET html_content = REPLACE(html_content, '</p></div>', '</p><img data-slot="image" alt="메뉴 이미지" style="flex:1;min-height:0;width:100%;object-fit:cover"></div>')
WHERE (poster_id BETWEEN 9001 AND 9016 OR poster_id BETWEEN 9101 AND 9200)
  AND html_content NOT LIKE '%data-slot="image"%';

UPDATE poster
SET html_content = REGEXP_REPLACE(html_content, '<img data-slot="image"[^>]*>',
                                  CONCAT('<img data-slot="image" alt="메뉴 이미지" src="${seedCdnBaseUrl}/',
                                         image_object_key, '">'))
WHERE poster_id BETWEEN 9001 AND 9016 OR poster_id BETWEEN 9101 AND 9200;
