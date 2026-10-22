/*
 * 기존 시험 시드(db/seed)가 넣은 행만 지운다 (#741 3절).
 * 실제 팀원이 만든 계정과 데이터는 남긴다.
 * 시드 행은 번호가 아니라 시드가 남긴 표식으로 고른다. 시드가 9000번대를 직접 넣은 탓에 실제 가입자도 그 번호대를 받았기 때문이다.
 * 시드가 돈 적 없는 DB(운영, 새 로컬)에서는 지울 것이 없어 아무것도 바뀌지 않는다.
 */

-- 시드 회원은 provider_user_id 가 seed-member- 로 시작한다
CREATE TEMPORARY TABLE base_seed_member AS
SELECT member_id FROM member WHERE provider_user_id LIKE 'seed-member-%';

-- 시드 점주는 이메일 도메인이 seed.lunchcatch.com 이다
CREATE TEMPORARY TABLE base_seed_owner AS
SELECT owner_id FROM owner WHERE email LIKE '%@seed.lunchcatch.com';

CREATE TEMPORARY TABLE base_seed_store AS
SELECT store_id FROM store WHERE owner_id IN (SELECT owner_id FROM base_seed_owner);

CREATE TEMPORARY TABLE base_seed_campaign AS
SELECT campaign_id FROM campaign
WHERE owner_id IN (SELECT owner_id FROM base_seed_owner)
   OR store_id IN (SELECT store_id FROM base_seed_store);

CREATE TEMPORARY TABLE base_seed_event AS
SELECT coupon_event_id FROM coupon_event WHERE campaign_id IN (SELECT campaign_id FROM base_seed_campaign);

CREATE TEMPORARY TABLE base_seed_member_coupon AS
SELECT member_coupon_id FROM member_coupon
WHERE coupon_event_id IN (SELECT coupon_event_id FROM base_seed_event)
   OR member_id IN (SELECT member_id FROM base_seed_member);

CREATE TEMPORARY TABLE base_seed_serve AS
SELECT serve_id FROM serve_log
WHERE campaign_id IN (SELECT campaign_id FROM base_seed_campaign)
   OR member_id IN (SELECT member_id FROM base_seed_member);

CREATE TEMPORARY TABLE base_seed_payment AS
SELECT payment_id FROM payment WHERE owner_id IN (SELECT owner_id FROM base_seed_owner);

CREATE TEMPORARY TABLE base_seed_report AS
SELECT campaign_daily_report_id FROM campaign_daily_report
WHERE campaign_id IN (SELECT campaign_id FROM base_seed_campaign);

CREATE TEMPORARY TABLE base_seed_plan AS
SELECT budget_daily_plan_id FROM budget_daily_plan WHERE campaign_id IN (SELECT campaign_id FROM base_seed_campaign);

-- 쿠폰
DELETE FROM coupon_usage_history WHERE member_coupon_id IN (SELECT member_coupon_id FROM base_seed_member_coupon);
DELETE FROM member_coupon WHERE member_coupon_id IN (SELECT member_coupon_id FROM base_seed_member_coupon);
DELETE FROM coupon_inventory WHERE coupon_event_id IN (SELECT coupon_event_id FROM base_seed_event);
DELETE FROM coupon_event WHERE coupon_event_id IN (SELECT coupon_event_id FROM base_seed_event);
DELETE FROM coupon_daily_limit WHERE member_id IN (SELECT member_id FROM base_seed_member);

-- 광고 서빙
DELETE FROM deduction_result
WHERE serve_id IN (SELECT serve_id FROM base_seed_serve)
   OR campaign_id IN (SELECT campaign_id FROM base_seed_campaign);
DELETE FROM impression_log
WHERE campaign_id IN (SELECT campaign_id FROM base_seed_campaign)
   OR member_id IN (SELECT member_id FROM base_seed_member);
DELETE FROM swipe_log
WHERE campaign_id IN (SELECT campaign_id FROM base_seed_campaign)
   OR member_id IN (SELECT member_id FROM base_seed_member);
DELETE FROM wishlist
WHERE campaign_id IN (SELECT campaign_id FROM base_seed_campaign)
   OR member_id IN (SELECT member_id FROM base_seed_member);
DELETE FROM serve_log WHERE serve_id IN (SELECT serve_id FROM base_seed_serve);
DELETE FROM ad_candidate WHERE campaign_id IN (SELECT campaign_id FROM base_seed_campaign);
DELETE FROM budget_hourly_target WHERE budget_daily_plan_id IN (SELECT budget_daily_plan_id FROM base_seed_plan);
DELETE FROM budget_daily_plan WHERE budget_daily_plan_id IN (SELECT budget_daily_plan_id FROM base_seed_plan);
DELETE FROM feed_filter_category WHERE member_id IN (SELECT member_id FROM base_seed_member);
DELETE FROM feed_filter WHERE member_id IN (SELECT member_id FROM base_seed_member);
DELETE FROM feed_visit_daily WHERE member_id IN (SELECT member_id FROM base_seed_member);

-- 정산
DELETE FROM settlement_mismatch
WHERE (check_type = 'PG_VS_CHARGE' AND target_id IN (SELECT payment_id FROM base_seed_payment))
   OR (check_type <> 'PG_VS_CHARGE' AND target_id IN (SELECT campaign_id FROM base_seed_campaign));
DELETE FROM point_ledger WHERE owner_id IN (SELECT owner_id FROM base_seed_owner);
DELETE FROM refund_request WHERE owner_id IN (SELECT owner_id FROM base_seed_owner);
DELETE FROM pg_charge_reconciliation_pending WHERE payment_id IN (SELECT payment_id FROM base_seed_payment);
DELETE FROM payment_reconciliation_outbox WHERE payment_id IN (SELECT payment_id FROM base_seed_payment);
DELETE FROM payment WHERE payment_id IN (SELECT payment_id FROM base_seed_payment);
DELETE FROM point_account WHERE owner_id IN (SELECT owner_id FROM base_seed_owner);

-- 분석 (캠페인, 점주, 회원 단위)
DELETE FROM campaign_daily_impression_reason_report WHERE campaign_daily_report_id IN (SELECT campaign_daily_report_id FROM base_seed_report);
DELETE FROM campaign_daily_slot_report WHERE campaign_daily_report_id IN (SELECT campaign_daily_report_id FROM base_seed_report);
DELETE FROM campaign_hourly_report WHERE campaign_daily_report_id IN (SELECT campaign_daily_report_id FROM base_seed_report);
DELETE FROM campaign_daily_report_history WHERE campaign_daily_report_id IN (SELECT campaign_daily_report_id FROM base_seed_report);
DELETE FROM campaign_daily_report WHERE campaign_daily_report_id IN (SELECT campaign_daily_report_id FROM base_seed_report);
DELETE FROM daily_campaign_fraud_analytics WHERE campaign_id IN (SELECT campaign_id FROM base_seed_campaign);
DELETE FROM daily_campaign_not_billed_analytics WHERE campaign_id IN (SELECT campaign_id FROM base_seed_campaign);
DELETE FROM daily_invalid_analytics
WHERE campaign_id IN (SELECT campaign_id FROM base_seed_campaign)
   OR member_id IN (SELECT member_id FROM base_seed_member);
DELETE FROM daily_owner_spend WHERE owner_id IN (SELECT owner_id FROM base_seed_owner);
DELETE FROM daily_member_usage WHERE member_id IN (SELECT member_id FROM base_seed_member);
DELETE FROM event_log
WHERE member_id IN (SELECT member_id FROM base_seed_member)
   OR campaign_id IN (SELECT campaign_id FROM base_seed_campaign)
   OR store_id IN (SELECT store_id FROM base_seed_store);

-- 캠페인과 포스터
DELETE FROM poster_moderation_result
WHERE poster_id IN (SELECT poster_id FROM poster WHERE campaign_id IN (SELECT campaign_id FROM base_seed_campaign));
DELETE FROM poster WHERE campaign_id IN (SELECT campaign_id FROM base_seed_campaign);
DELETE FROM store_campaign_summary
WHERE campaign_id IN (SELECT campaign_id FROM base_seed_campaign)
   OR store_id IN (SELECT store_id FROM base_seed_store);
DELETE FROM campaign_status_log WHERE campaign_id IN (SELECT campaign_id FROM base_seed_campaign);
DELETE FROM campaign WHERE campaign_id IN (SELECT campaign_id FROM base_seed_campaign);

/*
 * 시드 템플릿은 실제 점주의 포스터가 쓰지 않을 때만 지운다.
 * 쓰고 있으면 외래 키가 막는다.
 */
CREATE TEMPORARY TABLE base_seed_template AS
SELECT t.template_id FROM template t
WHERE t.name = '시드 기본 템플릿'
  AND NOT EXISTS (SELECT 1 FROM poster p WHERE p.template_id = t.template_id);
DELETE FROM template_version WHERE template_id IN (SELECT template_id FROM base_seed_template);
DELETE FROM template WHERE template_id IN (SELECT template_id FROM base_seed_template);
UPDATE template_quota SET current_count = (SELECT COUNT(*) FROM template);

-- 가게와 점주
DELETE FROM store_audience WHERE store_id IN (SELECT store_id FROM base_seed_store);
DELETE FROM store_menu_image WHERE store_menu_id IN (SELECT store_menu_id FROM store_menu WHERE store_id IN (SELECT store_id FROM base_seed_store));
DELETE FROM store_menu WHERE store_id IN (SELECT store_id FROM base_seed_store);
DELETE FROM store_image WHERE store_id IN (SELECT store_id FROM base_seed_store);
DELETE FROM store_business_hour WHERE store_id IN (SELECT store_id FROM base_seed_store);
DELETE FROM store_registration_progress WHERE owner_id IN (SELECT owner_id FROM base_seed_owner);
DELETE FROM store WHERE store_id IN (SELECT store_id FROM base_seed_store);
DELETE FROM owner_terms_agreement WHERE owner_id IN (SELECT owner_id FROM base_seed_owner);
DELETE FROM batch_execution_log WHERE owner_id IN (SELECT owner_id FROM base_seed_owner);
DELETE FROM owner WHERE owner_id IN (SELECT owner_id FROM base_seed_owner);

-- 회원
DELETE FROM kakao_unlink_failure WHERE member_id IN (SELECT member_id FROM base_seed_member);
DELETE FROM member_withdrawal_history WHERE member_id IN (SELECT member_id FROM base_seed_member);
DELETE FROM member_profile WHERE member_id IN (SELECT member_id FROM base_seed_member);
DELETE FROM member WHERE member_id IN (SELECT member_id FROM base_seed_member);

/*
 * 시드 관리자는 admin_id 9001 의 superadmin 이다.
 * 개발 서버처럼 seed-admin.sh 가 먼저 만든 superadmin 은 번호가 달라 남는다.
 */
DELETE FROM admin WHERE admin_id = 9001 AND login_id = 'superadmin';

/*
 * 시드 이력 테이블.
 * 시드가 돈 적 없으면 없다.
 */
DROP TABLE IF EXISTS flyway_history_seed;

DROP TEMPORARY TABLE base_seed_template;
DROP TEMPORARY TABLE base_seed_plan;
DROP TEMPORARY TABLE base_seed_report;
DROP TEMPORARY TABLE base_seed_payment;
DROP TEMPORARY TABLE base_seed_serve;
DROP TEMPORARY TABLE base_seed_member_coupon;
DROP TEMPORARY TABLE base_seed_event;
DROP TEMPORARY TABLE base_seed_campaign;
DROP TEMPORARY TABLE base_seed_store;
DROP TEMPORARY TABLE base_seed_owner;
DROP TEMPORARY TABLE base_seed_member;
