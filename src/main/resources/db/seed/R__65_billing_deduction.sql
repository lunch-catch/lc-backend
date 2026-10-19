/*
 * 광고 서빙 시드(R__50)의 유효 노출과 짝인 정산 기록이다(#442).
 * 실제로는 유효 노출을 저장하는 트랜잭션에서 노출 차감이 함께 커밋된다. 노출만 있으면 대사와 분석 집계가 어긋난다.
 * 노출은 impression_log 를 읽지 않고 R__50 과 같은 규칙으로 serve_id 를 만든다. 파일 순서에 기대지 않는다.
 * 규칙: 회원 m(9001~9010) 의 영업일은 MOD(m, 6) + 1 일 전이고, 자리 n(1~9) 의 캠페인은 9001 + MOD(m + n, 12) 다.
 * 자리 10 은 무효 노출이라 정산 기록이 없다. 캠페인 c 의 점주는 c 와 같은 번호다.
 * R__50 의 규칙이 바뀌면 이 파일도 같이 고친다.
 * 노출이 닿은 캠페인-영업일마다 그날 예산 계획, 예약, 노출별 차감, 해제를 넣고 점주마다 충전 한 건과 포인트 계정을 둔다.
 * 해제는 예약에서 그 영업일에 넣은 차감을 뺀 값이다.
 * 같은 영업일에 나중 날짜의 기동이 노출을 더 만들면 차감만 늘고 해제는 그대로다. 그 하루의 합이 노출 수만큼 어긋난다.
 * 지우려면 owner_id 9001~9012 인 point_ledger 와 deduction_result, 계획, 결제(PK 9101~9112)를 지운다.
 */
INSERT INTO point_account (point_account_id, owner_id, created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 9001 UNION ALL SELECT n + 1 FROM seq WHERE n < 9012)
SELECT n, n, NOW(6), NOW(6)
FROM seq
ON DUPLICATE KEY UPDATE point_account.point_account_id = point_account.point_account_id;

/*
 * 점주마다 충전 한 건이다. 노출 영업일(1~6일 전)보다 앞선 7일 전에 100000 을 넣는다.
 * 하루 예약이 10000 이고 해제로 대부분 돌아와 오래 쓴다.
 */
INSERT INTO payment (payment_id, owner_id, order_id, pg_tid, amount, status, approved_at, created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 9001 UNION ALL SELECT n + 1 FROM seq WHERE n < 9012)
SELECT n + 100, n, CONCAT('seed_ord_', n + 100), CONCAT('seed_tid_', n + 100), 100000, 'APPROVED',
       TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), '10:00:05'),
       TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), '10:00:00'),
       TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), '10:00:05')
FROM seq
ON DUPLICATE KEY UPDATE payment.payment_id = payment.payment_id;

INSERT INTO point_ledger (point_ledger_id, owner_id, payment_id, entry_type, source, amount, created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 9001 UNION ALL SELECT n + 1 FROM seq WHERE n < 9012)
SELECT n + 100, n, n + 100, 'CHARGE', 'REALTIME', 100000,
       TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), '10:00:05'),
       TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), '10:00:05')
FROM seq
ON DUPLICATE KEY UPDATE point_ledger.point_ledger_id = point_ledger.point_ledger_id;

-- 그 영업일의 예산 계획이다. 단가는 오늘 계획과 같이 10 이다
INSERT INTO budget_daily_plan (campaign_id, owner_id, business_date, daily_budget, unit_price, paused,
                               created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10),
               feed (m, d) AS (SELECT 9000 + n, DATE_SUB('${seedBusinessDate}', INTERVAL MOD(9000 + n, 6) + 1 DAY) FROM seq),
               imp (serve_id, campaign_id, d, slot) AS (
                   SELECT UNHEX(MD5(CONCAT('seed-serve-', feed.m, '-', feed.d, '-', seq.n))),
                          9001 + MOD(feed.m + seq.n, 12), feed.d, seq.n
                   FROM feed
                   JOIN seq ON seq.n BETWEEN 1 AND 9)
SELECT campaign_id, campaign_id, d, 10000, 10, 0, NOW(6), NOW(6)
FROM imp
GROUP BY campaign_id, d
ON DUPLICATE KEY UPDATE budget_daily_plan.campaign_id = budget_daily_plan.campaign_id;

-- 00:00 예약이다
INSERT INTO point_ledger (owner_id, campaign_id, business_date, entry_type, source, amount, created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10),
               feed (m, d) AS (SELECT 9000 + n, DATE_SUB('${seedBusinessDate}', INTERVAL MOD(9000 + n, 6) + 1 DAY) FROM seq),
               imp (serve_id, campaign_id, d, slot) AS (
                   SELECT UNHEX(MD5(CONCAT('seed-serve-', feed.m, '-', feed.d, '-', seq.n))),
                          9001 + MOD(feed.m + seq.n, 12), feed.d, seq.n
                   FROM feed
                   JOIN seq ON seq.n BETWEEN 1 AND 9)
SELECT campaign_id, campaign_id, d, 'RESERVE', 'RECONCILIATION', -10000, TIMESTAMP(d, '00:00:05'), TIMESTAMP(d, '00:00:05')
FROM imp
GROUP BY campaign_id, d
ON DUPLICATE KEY UPDATE point_ledger.point_ledger_id = point_ledger.point_ledger_id;

-- 노출마다 판정이다. 예산 안이라 모두 BILLED 다
INSERT INTO deduction_result (serve_id, campaign_id, owner_id, business_date, billing_status, unit_price,
                              created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10),
               feed (m, d) AS (SELECT 9000 + n, DATE_SUB('${seedBusinessDate}', INTERVAL MOD(9000 + n, 6) + 1 DAY) FROM seq),
               imp (serve_id, campaign_id, d, slot) AS (
                   SELECT UNHEX(MD5(CONCAT('seed-serve-', feed.m, '-', feed.d, '-', seq.n))),
                          9001 + MOD(feed.m + seq.n, 12), feed.d, seq.n
                   FROM feed
                   JOIN seq ON seq.n BETWEEN 1 AND 9)
SELECT serve_id, campaign_id, campaign_id, d, 'BILLED', 10,
       TIMESTAMP(d, '10:30:00') + INTERVAL slot + 1 SECOND, TIMESTAMP(d, '10:30:00') + INTERVAL slot + 1 SECOND
FROM imp
ON DUPLICATE KEY UPDATE deduction_result.serve_id = deduction_result.serve_id;

-- BILLED 마다 차감 한 행이다. 금액은 -단가이고 잔액에는 들어가지 않는다
INSERT INTO point_ledger (owner_id, campaign_id, serve_id, entry_type, source, amount, created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10),
               feed (m, d) AS (SELECT 9000 + n, DATE_SUB('${seedBusinessDate}', INTERVAL MOD(9000 + n, 6) + 1 DAY) FROM seq),
               imp (serve_id, campaign_id, d, slot) AS (
                   SELECT UNHEX(MD5(CONCAT('seed-serve-', feed.m, '-', feed.d, '-', seq.n))),
                          9001 + MOD(feed.m + seq.n, 12), feed.d, seq.n
                   FROM feed
                   JOIN seq ON seq.n BETWEEN 1 AND 9)
SELECT campaign_id, campaign_id, serve_id, 'DEDUCT', 'REALTIME', -10,
       TIMESTAMP(d, '10:30:00') + INTERVAL slot + 1 SECOND, TIMESTAMP(d, '10:30:00') + INTERVAL slot + 1 SECOND
FROM imp
ON DUPLICATE KEY UPDATE point_ledger.point_ledger_id = point_ledger.point_ledger_id;

-- 13:30 해제다. 예약에서 그 영업일의 차감을 뺀 나머지를 돌려준다
INSERT INTO point_ledger (owner_id, campaign_id, business_date, entry_type, source, amount, created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10),
               feed (m, d) AS (SELECT 9000 + n, DATE_SUB('${seedBusinessDate}', INTERVAL MOD(9000 + n, 6) + 1 DAY) FROM seq),
               imp (serve_id, campaign_id, d, slot) AS (
                   SELECT UNHEX(MD5(CONCAT('seed-serve-', feed.m, '-', feed.d, '-', seq.n))),
                          9001 + MOD(feed.m + seq.n, 12), feed.d, seq.n
                   FROM feed
                   JOIN seq ON seq.n BETWEEN 1 AND 9)
SELECT campaign_id, campaign_id, d, 'RELEASE', 'RECONCILIATION', 10000 - 10 * COUNT(*),
       TIMESTAMP(d, '13:30:05'), TIMESTAMP(d, '13:30:05')
FROM imp
GROUP BY campaign_id, d
ON DUPLICATE KEY UPDATE point_ledger.point_ledger_id = point_ledger.point_ledger_id;
