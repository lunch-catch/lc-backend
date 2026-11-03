/*
 * 충전 동시성 시험(charge_concurrency.js)의 점주와 로그인 세션을 넣는다.
 * 번호는 4,000만 대역이다. 다시 돌리면 그 대역의 결제, 원장, 점주를 지우고 다시 넣는다.
 * 부하 시험 DB 와 로컬에서만 쓴다. 개발과 운영에 넣지 않는다.
 *
 *   docker exec -i lunchcatch-mysql mysql -uroot -proot lunchcatch < loadtest/billing/seed.sql
 */
SET @lo = 40000001;
SET @hi = 40999999;
-- k6 의 OWNERS 보다 크거나 같아야 한다
SET @owners = 1000;
SET SESSION cte_max_recursion_depth = 1000000;

-- 1. 시험 대역의 결제, 원장, 점주를 지운다
DELETE FROM pg_charge_reconciliation_pending
WHERE payment_id IN (SELECT payment_id FROM payment WHERE owner_id BETWEEN @lo AND @hi);
DELETE FROM payment_reconciliation_outbox
WHERE payment_id IN (SELECT payment_id FROM payment WHERE owner_id BETWEEN @lo AND @hi);
DELETE FROM recharge_reservation_outbox
WHERE payment_id IN (SELECT payment_id FROM payment WHERE owner_id BETWEEN @lo AND @hi);
DELETE FROM point_ledger WHERE owner_id BETWEEN @lo AND @hi;
DELETE FROM payment WHERE owner_id BETWEEN @lo AND @hi;
DELETE FROM point_account WHERE owner_id BETWEEN @lo AND @hi;
DELETE FROM owner_session WHERE owner_id BETWEEN @lo AND @hi;
DELETE FROM owner WHERE owner_id BETWEEN @lo AND @hi;

-- 2. 가입을 마쳐 ACTIVE 이고 캠페인이 없는 점주를 넣는다
INSERT INTO owner (owner_id, email, password_hash, role, status, tutorial_viewed, suspended_at, suspension_reason,
                   withdrawn_at, last_login_at, created_at, updated_at)
WITH RECURSIVE n (i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i + 1 < @owners)
SELECT @lo + i, CONCAT('loadtest-', @lo + i, '@lunchcatch.test'), 'loadtest', 'OWNER', 'ACTIVE', 1, NULL, NULL,
       NULL, NULL, NOW(6), NOW(6)
FROM n;

/*
 * 3. 점주마다 로그인 세션을 하나씩 넣는다.
 * session_key 는 charge_concurrency.js 의 sessionKey() 와 같은 규칙으로 만든다.
 * 둘이 어긋나면 모든 요청이 401 이다.
 */
INSERT INTO owner_session (owner_session_id, session_key, owner_id, generation, rotation_version, refresh_token_hash,
                           previous_token_hash, previous_valid_until, expires_at, revoked_at, created_at, updated_at)
SELECT owner_id,
       LOWER(CONCAT(LPAD(HEX(owner_id), 8, '0'), '-0000-4000-8000-', LPAD(HEX(owner_id), 12, '0'))),
       owner_id, 0, 1, SHA2(CONCAT('loadtest-session-', owner_id), 256),
       NULL, NULL, DATE_ADD(NOW(6), INTERVAL 7 DAY), NULL, NOW(6), NOW(6)
FROM owner
WHERE owner_id BETWEEN @lo AND @hi;

SELECT COUNT(*) AS owners FROM owner WHERE owner_id BETWEEN @lo AND @hi;
