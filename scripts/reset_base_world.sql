/*
 * 기본 세계(db/base V2 ~ V8)와 시연 층을 다시 넣기 전에 한 번 돌린다 (#858).
 * 이미 기본 세계가 들어간 개발과 운영에서 V2 ~ V7 을 고친 코드로 올리면 체크섬이 어긋나 앱이 뜨지 않는다.
 * 이 파일은 계정은 남기고 날짜와 위치에 묶인 행만 지운 뒤 V2 ~ V8 과 반복 마이그레이션의 이력을 지운다.
 * 그다음 새 코드로 기동하면 V2 ~ V8, R__demo, R__templates_posters 가 그날 기준으로 다시 돈다.
 * 남기는 것은 admin, owner, member 와 탈퇴 이력, 카카오 연결 해제 실패 행이다. 실제 가입자, 기본 세계, 시연 계정 모두 남는다.
 * 지우는 범위는 번호 대역이 아니라 기본 세계와 시연 층이 넣은 계정에 딸린 행이다.
 * 기본 세계 뒤에 실제로 가입한 회원과 점주는 자동 증가 번호가 시연 대역 위(2,000만 이상)라 대역으로 고르면 함께 지워지기 때문이다.
 * 날짜로만 묶인 전체 분석 표와 배치 실행 기록은 최근 61일을 지운다. 기본 세계가 다시 채운다.
 */

-- 지울 계정 범위: 기본 세계 회원 1 ~ 3000, 점주 1 ~ 500 과 시연 회원 201 ~ 210, 시연 점주 11 ~ 15
CREATE TEMPORARY TABLE reset_member AS
SELECT member_id FROM member WHERE member_id BETWEEN 10000001 AND 10003000 OR member_id BETWEEN 20000201 AND 20000210;
CREATE TEMPORARY TABLE reset_owner AS
SELECT owner_id FROM owner WHERE owner_id BETWEEN 10000001 AND 10000500 OR owner_id BETWEEN 20000011 AND 20000015;
CREATE TEMPORARY TABLE reset_store AS
SELECT store_id FROM store WHERE owner_id IN (SELECT owner_id FROM reset_owner);
CREATE TEMPORARY TABLE reset_campaign AS
SELECT campaign_id FROM campaign
WHERE owner_id IN (SELECT owner_id FROM reset_owner) OR store_id IN (SELECT store_id FROM reset_store);
CREATE TEMPORARY TABLE reset_event AS
SELECT coupon_event_id FROM coupon_event WHERE campaign_id IN (SELECT campaign_id FROM reset_campaign);
CREATE TEMPORARY TABLE reset_member_coupon AS
SELECT member_coupon_id FROM member_coupon
WHERE coupon_event_id IN (SELECT coupon_event_id FROM reset_event) OR member_id IN (SELECT member_id FROM reset_member);
CREATE TEMPORARY TABLE reset_serve AS
SELECT serve_id FROM serve_log
WHERE campaign_id IN (SELECT campaign_id FROM reset_campaign) OR member_id IN (SELECT member_id FROM reset_member);
CREATE TEMPORARY TABLE reset_payment AS
SELECT payment_id FROM payment WHERE owner_id IN (SELECT owner_id FROM reset_owner);
CREATE TEMPORARY TABLE reset_report AS
SELECT campaign_daily_report_id FROM campaign_daily_report WHERE campaign_id IN (SELECT campaign_id FROM reset_campaign);

-- 쿠폰
DELETE FROM coupon_usage_history WHERE member_coupon_id IN (SELECT member_coupon_id FROM reset_member_coupon);
DELETE FROM member_coupon WHERE member_coupon_id IN (SELECT member_coupon_id FROM reset_member_coupon);
DELETE FROM coupon_inventory WHERE coupon_event_id IN (SELECT coupon_event_id FROM reset_event);
DELETE FROM coupon_event WHERE coupon_event_id IN (SELECT coupon_event_id FROM reset_event);
DELETE FROM coupon_daily_limit WHERE member_id IN (SELECT member_id FROM reset_member);

-- 광고 서빙
DELETE FROM deduction_result
WHERE serve_id IN (SELECT serve_id FROM reset_serve) OR campaign_id IN (SELECT campaign_id FROM reset_campaign);
DELETE FROM impression_log
WHERE campaign_id IN (SELECT campaign_id FROM reset_campaign) OR member_id IN (SELECT member_id FROM reset_member);
DELETE FROM swipe_log
WHERE campaign_id IN (SELECT campaign_id FROM reset_campaign) OR member_id IN (SELECT member_id FROM reset_member);
DELETE FROM wishlist
WHERE campaign_id IN (SELECT campaign_id FROM reset_campaign) OR member_id IN (SELECT member_id FROM reset_member);
DELETE FROM serve_log WHERE serve_id IN (SELECT serve_id FROM reset_serve);
DELETE FROM ad_candidate WHERE campaign_id IN (SELECT campaign_id FROM reset_campaign);
DELETE FROM budget_daily_plan WHERE campaign_id IN (SELECT campaign_id FROM reset_campaign);
DELETE FROM feed_filter_category WHERE member_id IN (SELECT member_id FROM reset_member);
DELETE FROM feed_filter WHERE member_id IN (SELECT member_id FROM reset_member);
DELETE FROM feed_visit_daily WHERE member_id IN (SELECT member_id FROM reset_member);

-- 정산. 불일치와 포인트 정책은 기본 세계가 넣은 번호(B + 1 ~ 99)와 지울 결제, 캠페인을 가리키는 행이다
DELETE FROM settlement_mismatch
WHERE settlement_mismatch_id BETWEEN 10000001 AND 10000099
   OR (check_type = 'PG_VS_CHARGE' AND target_id IN (SELECT payment_id FROM reset_payment))
   OR (check_type <> 'PG_VS_CHARGE' AND target_id IN (SELECT campaign_id FROM reset_campaign));
DELETE FROM point_ledger WHERE owner_id IN (SELECT owner_id FROM reset_owner);
DELETE FROM refund_request WHERE owner_id IN (SELECT owner_id FROM reset_owner);
DELETE FROM pg_charge_reconciliation_pending WHERE payment_id IN (SELECT payment_id FROM reset_payment);
DELETE FROM payment_reconciliation_outbox WHERE payment_id IN (SELECT payment_id FROM reset_payment);
DELETE FROM payment WHERE payment_id IN (SELECT payment_id FROM reset_payment);
DELETE FROM point_account WHERE owner_id IN (SELECT owner_id FROM reset_owner);
DELETE FROM point_policy WHERE point_policy_id BETWEEN 10000001 AND 10000099;

-- 분석 (캠페인, 점주, 회원 단위)
DELETE FROM campaign_daily_impression_reason_report WHERE campaign_daily_report_id IN (SELECT campaign_daily_report_id FROM reset_report);
DELETE FROM campaign_daily_slot_report WHERE campaign_daily_report_id IN (SELECT campaign_daily_report_id FROM reset_report);
DELETE FROM campaign_hourly_report WHERE campaign_daily_report_id IN (SELECT campaign_daily_report_id FROM reset_report);
DELETE FROM campaign_daily_report_history WHERE campaign_daily_report_id IN (SELECT campaign_daily_report_id FROM reset_report);
DELETE FROM campaign_daily_report WHERE campaign_daily_report_id IN (SELECT campaign_daily_report_id FROM reset_report);
DELETE FROM daily_campaign_fraud_analytics WHERE campaign_id IN (SELECT campaign_id FROM reset_campaign);
DELETE FROM daily_campaign_not_billed_analytics WHERE campaign_id IN (SELECT campaign_id FROM reset_campaign);
DELETE FROM daily_invalid_analytics
WHERE campaign_id IN (SELECT campaign_id FROM reset_campaign) OR member_id IN (SELECT member_id FROM reset_member);
DELETE FROM daily_owner_spend WHERE owner_id IN (SELECT owner_id FROM reset_owner);
DELETE FROM daily_member_usage WHERE member_id IN (SELECT member_id FROM reset_member);
DELETE FROM event_log
WHERE member_id IN (SELECT member_id FROM reset_member)
   OR campaign_id IN (SELECT campaign_id FROM reset_campaign)
   OR store_id IN (SELECT store_id FROM reset_store);

-- 분석 (날짜 단위 전체 집계). 기본 세계가 최근 60일을 다시 넣는다
DELETE FROM daily_funnel_analytics WHERE business_date >= CURDATE() - INTERVAL 61 DAY;
DELETE FROM daily_settlement_analytics WHERE business_date >= CURDATE() - INTERVAL 61 DAY;
DELETE FROM daily_store_analytics WHERE business_date >= CURDATE() - INTERVAL 61 DAY;
DELETE FROM daily_member_analytics WHERE business_date >= CURDATE() - INTERVAL 61 DAY;
DELETE FROM daily_segment_analytics WHERE business_date >= CURDATE() - INTERVAL 61 DAY;

/*
 * 배치 실행 기록이다. 기본 세계가 최근 60일의 SUCCESS 를 다시 넣는다.
 * 그 사이 실제로 돈 기록이 남으면 기본 세계 행이 건너뛰어진다.
 */
DELETE FROM batch_execution_log
WHERE batch_execution_log_id BETWEEN 10000001 AND 10099999 OR business_date >= CURDATE() - INTERVAL 61 DAY;

-- 캠페인과 포스터
DELETE FROM poster_moderation_result
WHERE poster_id IN (SELECT poster_id FROM poster WHERE campaign_id IN (SELECT campaign_id FROM reset_campaign));
DELETE FROM poster WHERE campaign_id IN (SELECT campaign_id FROM reset_campaign);
DELETE FROM store_campaign_summary
WHERE campaign_id IN (SELECT campaign_id FROM reset_campaign) OR store_id IN (SELECT store_id FROM reset_store);
DELETE FROM campaign_status_log WHERE campaign_id IN (SELECT campaign_id FROM reset_campaign);
DELETE FROM campaign WHERE campaign_id IN (SELECT campaign_id FROM reset_campaign);

-- 템플릿은 기본 세계(B + 1 ~ 12)와 예전 시연 템플릿(2,000만 + 1) 중 남은 포스터가 쓰지 않는 것만 지운다
DELETE FROM template_version
WHERE (template_id BETWEEN 10000001 AND 10000012 OR template_id = 20000001)
  AND template_id NOT IN (SELECT template_id FROM poster);
DELETE FROM template
WHERE (template_id BETWEEN 10000001 AND 10000012 OR template_id = 20000001)
  AND template_id NOT IN (SELECT template_id FROM poster);
UPDATE template_quota SET current_count = (SELECT COUNT(*) FROM template);

-- 가게 (위치와 수가 바뀌었다)
DELETE FROM store_audience WHERE store_id IN (SELECT store_id FROM reset_store);
DELETE FROM store_menu_image
WHERE store_menu_id IN (SELECT store_menu_id FROM store_menu WHERE store_id IN (SELECT store_id FROM reset_store));
DELETE FROM store_menu WHERE store_id IN (SELECT store_id FROM reset_store);
DELETE FROM store_image WHERE store_id IN (SELECT store_id FROM reset_store);
DELETE FROM store_business_hour WHERE store_id IN (SELECT store_id FROM reset_store);
DELETE FROM store_registration_progress WHERE owner_id IN (SELECT owner_id FROM reset_owner);
DELETE FROM store WHERE store_id IN (SELECT store_id FROM reset_store);

-- 회원 프로필 (위치가 바뀌었다). 회원 행은 남긴다
DELETE FROM member_profile WHERE member_id IN (SELECT member_id FROM reset_member);

/*
 * 이력이다. V1 기록은 남긴다.
 * V2 ~ V8 은 버전이 낮은 순서로 다시 돌아야 해서 모두 지운다.
 */
DELETE FROM flyway_history_base WHERE version IN ('2', '3', '4', '5', '6', '7', '8') OR (version IS NULL AND type = 'SQL');

DROP TEMPORARY TABLE reset_report;
DROP TEMPORARY TABLE reset_payment;
DROP TEMPORARY TABLE reset_serve;
DROP TEMPORARY TABLE reset_member_coupon;
DROP TEMPORARY TABLE reset_event;
DROP TEMPORARY TABLE reset_campaign;
DROP TEMPORARY TABLE reset_store;
DROP TEMPORARY TABLE reset_owner;
DROP TEMPORARY TABLE reset_member;

SELECT CONCAT('reset done owners=', (SELECT COUNT(*) FROM owner), ' members=', (SELECT COUNT(*) FROM member),
              ' admins=', (SELECT COUNT(*) FROM admin), ' base_history=', (SELECT COUNT(*) FROM flyway_history_base)) AS result;
