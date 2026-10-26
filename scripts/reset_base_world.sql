/*
 * 기본 세계(db/base V2 ~ V10)와 시연 층을 다시 넣기 전에 한 번 돌린다 (#858).
 * 이미 기본 세계가 들어간 개발과 운영에서 V2 ~ V7 을 고친 코드로 올리면 체크섬이 어긋나 앱이 뜨지 않는다.
 * 이 파일은 계정은 남기고 날짜와 위치에 묶인 행만 지운 뒤 V2 ~ V10 과 반복 마이그레이션의 이력을 지운다.
 * 그다음 새 코드로 기동하면 V2 ~ V10, R__demo, R__templates_posters 가 그날 기준으로 다시 돈다.
 * 남기는 것은 admin, owner, member 와 탈퇴 이력, 카카오 연결 해제 실패 행이다. 실제 가입자, 기본 세계, 시연 계정 모두 남는다.
 * 지우는 범위는 번호 대역이 아니라 기본 세계와 시연 층이 넣은 계정에 딸린 행이다.
 * 기본 세계 뒤에 실제로 가입한 회원과 점주는 자동 증가 번호가 시연 대역 위(2,000만 이상)라 대역으로 고르면 함께 지워지기 때문이다.
 * 날짜로만 묶인 전체 분석 표와 배치 실행 기록은 최근 61일을 지운다. 기본 세계가 다시 채운다.
 * 기본 세계 번호 대역에 남은 실제 행은 5,000만 대역으로 옮긴다.
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

/*
 * 옮기지 않는 표에 실제 행이 기본 세계 번호 대역(1,000만)으로 들어와 있으면 아무것도 지우기 전에 멈춘다.
 * 이 표들은 다른 표가 번호로 많이 가리켜서 이 파일이 옮기지 않는다. 걸리면 그 행을 먼저 따로 옮긴다.
 * MySQL 은 저장 프로시저 밖에서 SIGNAL 을 쓸 수 없어서, 걸리면 두 행을 돌려주는 하위 쿼리로 오류(1242)를 낸다.
 */
SET @real_in_band = (SELECT COUNT(*) FROM campaign WHERE campaign_id BETWEEN 10000001 AND 19999999
                       AND campaign_id NOT IN (SELECT campaign_id FROM reset_campaign))
                  + (SELECT COUNT(*) FROM coupon_event WHERE coupon_event_id BETWEEN 10000001 AND 19999999
                       AND coupon_event_id NOT IN (SELECT coupon_event_id FROM reset_event))
                  + (SELECT COUNT(*) FROM member_coupon WHERE member_coupon_id BETWEEN 10000001 AND 19999999
                       AND member_coupon_id NOT IN (SELECT member_coupon_id FROM reset_member_coupon))
                  + (SELECT COUNT(*) FROM payment WHERE payment_id BETWEEN 10000001 AND 19999999
                       AND payment_id NOT IN (SELECT payment_id FROM reset_payment))
                  + (SELECT COUNT(*) FROM campaign_daily_report WHERE campaign_daily_report_id BETWEEN 10000001 AND 19999999
                       AND campaign_daily_report_id NOT IN (SELECT campaign_daily_report_id FROM reset_report))
                  + (SELECT COUNT(*) FROM refund_request WHERE refund_request_id BETWEEN 10000001 AND 19999999
                       AND owner_id NOT IN (SELECT owner_id FROM reset_owner));
-- 한 문장에서 같은 임시 표를 두 번 열 수 없어서 reset_owner 를 쓰는 둘째 검사는 따로 더한다
SET @real_in_band = @real_in_band
                  + (SELECT COUNT(*) FROM point_ledger WHERE point_ledger_id BETWEEN 10000001 AND 19999999
                       AND owner_id NOT IN (SELECT owner_id FROM reset_owner));
SELECT CONCAT('real rows in base band (must be 0): ', @real_in_band) AS guard;
DO (SELECT 1 FROM (SELECT 1 AS k UNION ALL SELECT 2) x WHERE @real_in_band > 0);

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
/*
 * 시간대별 목표 표는 V8 이 지운다. V8 전의 DB(운영)에는 남아 있고 예산 계획을 외래 키로 가리킨다.
 * 표가 있을 때만 먼저 지운다. 저장 프로시저 밖이라 IF 대신 준비된 문장으로 고른다.
 */
SET @hourly = IF(EXISTS (SELECT 1 FROM information_schema.tables
                         WHERE table_schema = DATABASE() AND table_name = 'budget_hourly_target'),
                 'DELETE FROM budget_hourly_target WHERE budget_daily_plan_id IN (SELECT budget_daily_plan_id FROM budget_daily_plan WHERE campaign_id IN (SELECT campaign_id FROM reset_campaign))',
                 'DO 0');
PREPARE hourly_stmt FROM @hourly;
EXECUTE hourly_stmt;
DEALLOCATE PREPARE hourly_stmt;
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

-- 템플릿은 기본 세계(B + 1 ~ 14)와 예전 시연 템플릿(2,000만 + 1) 중 남은 포스터가 쓰지 않는 것만 지운다
DELETE FROM template_version
WHERE (template_id BETWEEN 10000001 AND 10000014 OR template_id = 20000001)
  AND template_id NOT IN (SELECT template_id FROM poster);
DELETE FROM template
WHERE (template_id BETWEEN 10000001 AND 10000014 OR template_id = 20000001)
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
 * 여기까지 지우고 기본 세계 번호 대역(1,000만)에 남은 행은 실제 데이터다.
 * 가게를 500개로 늘리면서 그 사이 실제로 등록된 가게와 메뉴, 이미지, 영업시간이 새 기본 세계가 쓸 번호를 이미 받은 경우가 있다 (개발의 가게 10000301).
 * 그대로 두면 V3 가 그 번호를 건너뛰어 기본 세계 가게가 빠지고 V6 이 좌표를 읽지 못한다.
 * 지우지 않고 번호에 4,000만을 더해 5,000만 대역으로 옮긴다. 가리키는 열에도 같은 값을 더하므로 서로 맞는다.
 * 외래 키는 부모와 자식을 따로 바꾸는 동안만 끈다.
 */
SET FOREIGN_KEY_CHECKS = 0;
UPDATE store SET store_id = store_id + 40000000 WHERE store_id BETWEEN 10000001 AND 19999999;
UPDATE ad_candidate SET store_id = store_id + 40000000 WHERE store_id BETWEEN 10000001 AND 19999999;
UPDATE campaign SET store_id = store_id + 40000000 WHERE store_id BETWEEN 10000001 AND 19999999;
UPDATE campaign_daily_report SET store_id = store_id + 40000000 WHERE store_id BETWEEN 10000001 AND 19999999;
UPDATE coupon_event SET store_id = store_id + 40000000 WHERE store_id BETWEEN 10000001 AND 19999999;
UPDATE coupon_usage_history SET store_id = store_id + 40000000 WHERE store_id BETWEEN 10000001 AND 19999999;
UPDATE event_log SET store_id = store_id + 40000000 WHERE store_id BETWEEN 10000001 AND 19999999;
UPDATE impression_log SET store_id = store_id + 40000000 WHERE store_id BETWEEN 10000001 AND 19999999;
UPDATE serve_log SET store_id = store_id + 40000000 WHERE store_id BETWEEN 10000001 AND 19999999;
UPDATE store_audience SET store_id = store_id + 40000000 WHERE store_id BETWEEN 10000001 AND 19999999;
UPDATE store_business_hour SET store_id = store_id + 40000000 WHERE store_id BETWEEN 10000001 AND 19999999;
UPDATE store_campaign_summary SET store_id = store_id + 40000000 WHERE store_id BETWEEN 10000001 AND 19999999;
UPDATE store_image SET store_id = store_id + 40000000 WHERE store_id BETWEEN 10000001 AND 19999999;
UPDATE store_menu SET store_id = store_id + 40000000 WHERE store_id BETWEEN 10000001 AND 19999999;
UPDATE store_registration_progress SET store_id = store_id + 40000000 WHERE store_id BETWEEN 10000001 AND 19999999;
UPDATE wishlist SET store_id = store_id + 40000000 WHERE store_id BETWEEN 10000001 AND 19999999;

-- 메뉴 번호와 메뉴를 가리키는 열
UPDATE store_menu SET store_menu_id = store_menu_id + 40000000 WHERE store_menu_id BETWEEN 10000001 AND 19999999;
UPDATE store_menu_image SET store_menu_id = store_menu_id + 40000000 WHERE store_menu_id BETWEEN 10000001 AND 19999999;
UPDATE campaign SET target_menu_id = target_menu_id + 40000000 WHERE target_menu_id BETWEEN 10000001 AND 19999999;
UPDATE coupon_event SET target_menu_id = target_menu_id + 40000000 WHERE target_menu_id BETWEEN 10000001 AND 19999999;
UPDATE store_campaign_summary SET target_menu_id = target_menu_id + 40000000 WHERE target_menu_id BETWEEN 10000001 AND 19999999;

-- 다른 표가 가리키지 않는 표는 자기 번호만 옮긴다
UPDATE store_image SET store_image_id = store_image_id + 40000000 WHERE store_image_id BETWEEN 10000001 AND 19999999;
UPDATE store_business_hour SET store_business_hour_id = store_business_hour_id + 40000000 WHERE store_business_hour_id BETWEEN 10000001 AND 19999999;
UPDATE store_menu_image SET store_menu_image_id = store_menu_image_id + 40000000 WHERE store_menu_image_id BETWEEN 10000001 AND 19999999;
UPDATE store_registration_progress SET store_registration_progress_id = store_registration_progress_id + 40000000 WHERE store_registration_progress_id BETWEEN 10000001 AND 19999999;
UPDATE event_log SET event_log_id = event_log_id + 40000000 WHERE event_log_id BETWEEN 10000001 AND 19999999;
UPDATE wishlist SET wishlist_id = wishlist_id + 40000000 WHERE wishlist_id BETWEEN 10000001 AND 19999999;
UPDATE campaign_status_log SET campaign_status_log_id = campaign_status_log_id + 40000000 WHERE campaign_status_log_id BETWEEN 10000001 AND 19999999;
UPDATE campaign_hourly_report SET campaign_hourly_report_id = campaign_hourly_report_id + 40000000 WHERE campaign_hourly_report_id BETWEEN 10000001 AND 19999999;
UPDATE campaign_daily_slot_report SET campaign_daily_slot_report_id = campaign_daily_slot_report_id + 40000000 WHERE campaign_daily_slot_report_id BETWEEN 10000001 AND 19999999;
UPDATE campaign_daily_impression_reason_report SET campaign_daily_impression_reason_report_id = campaign_daily_impression_reason_report_id + 40000000 WHERE campaign_daily_impression_reason_report_id BETWEEN 10000001 AND 19999999;
UPDATE daily_invalid_analytics SET daily_invalid_analytics_id = daily_invalid_analytics_id + 40000000 WHERE daily_invalid_analytics_id BETWEEN 10000001 AND 19999999;
UPDATE poster_moderation_result SET poster_moderation_result_id = poster_moderation_result_id + 40000000 WHERE poster_moderation_result_id BETWEEN 10000001 AND 19999999;
UPDATE payment_reconciliation_outbox SET payment_reconciliation_outbox_id = payment_reconciliation_outbox_id + 40000000 WHERE payment_reconciliation_outbox_id BETWEEN 10000001 AND 19999999;
SET FOREIGN_KEY_CHECKS = 1;

/*
 * 이력이다. V1 기록은 남긴다.
 * V2 ~ V10 은 버전이 낮은 순서로 다시 돌아야 해서 모두 지운다.
 */
DELETE FROM flyway_history_base WHERE version IN ('2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12') OR (version IS NULL AND type = 'SQL');

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
