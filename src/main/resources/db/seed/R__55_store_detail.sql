/*
 * 기존 쿠폰·피드 시험 가게에 빠진 영업시간과 당일 캠페인 요약을 채운다.
 * 기존 등록값은 유지하며 시험 점주의 가게에만 추가한다.
 */
INSERT INTO store_business_hour (store_id, day_of_week, open_time, close_time, closed,
                                 break_start_time, break_end_time, last_order_time, created_at, updated_at)
WITH RECURSIVE dow (d) AS (SELECT 1 UNION ALL SELECT d + 1 FROM dow WHERE d < 7)
SELECT s.store_id, ELT(dow.d, 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'),
       '10:00', '22:00', 0, NULL, NULL, '21:30', NOW(6), NOW(6)
FROM store s
JOIN owner o ON o.owner_id = s.owner_id
CROSS JOIN dow
WHERE s.finalized = 1 AND s.owner_id = s.store_id
  AND (s.store_id BETWEEN 9001 AND 9016 OR s.store_id BETWEEN 9101 AND 9200)
  AND o.email = CONCAT('owner', s.store_id - 9000, '@seed.lunchcatch.com')
ON DUPLICATE KEY UPDATE store_business_hour.store_id = store_business_hour.store_id;

-- TODO: S3 메뉴 이미지가 준비되면 실제 객체를 검증한 메뉴 이미지 시드를 추가하고 상세 조회의 임시 null 허용을 복구한다.

-- 요약은 쿠폰·피드 시드가 참조하는 캠페인과 같은 할인 및 사용 시간을 갖는다.
INSERT INTO store_campaign_summary (store_id, campaign_id, business_date, campaign_status,
                                    discount_target_type, discount_type, discount_value, target_menu_id,
                                    usable_start_time, usable_end_time, created_at, updated_at)
SELECT s.store_id, c.campaign_id, '${seedBusinessDate}', c.status,
       c.discount_target_type, c.discount_type, c.discount_value, c.target_menu_id,
       c.usable_start_time, c.usable_end_time, NOW(6), NOW(6)
FROM store s
JOIN owner o ON o.owner_id = s.owner_id
JOIN campaign c ON c.store_id = s.store_id AND c.owner_id = s.owner_id AND c.campaign_id = s.store_id
WHERE s.finalized = 1 AND s.owner_id = s.store_id
  AND (s.store_id BETWEEN 9001 AND 9016 OR s.store_id BETWEEN 9101 AND 9200)
  AND o.email = CONCAT('owner', s.store_id - 9000, '@seed.lunchcatch.com')
  AND c.status = 'ACTIVE' AND '${seedBusinessDate}' BETWEEN c.start_date AND c.end_date
ON DUPLICATE KEY UPDATE store_campaign_summary.campaign_id = store_campaign_summary.campaign_id;
