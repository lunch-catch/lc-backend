/*
 * 기본 세계 정산 (#741, WORLD_SPEC "정산 (V5)").
 * 점주 n 의 owner_id 는 10000000 + n 이고 캠페인 n 의 campaign_id 도 10000000 + n 이다.
 * 이 파일은 충전, 환불, 조정 원장(point_ledger 10000001 ~ 10099999)만 쓴다. 예약, 해제, 차감과 예산 계획은 V6 가 쓴다.
 * 승인 충전 합은 점주 1 ~ 250 마다 정확히 300,000 이고 점주 196 ~ 205 는 승인 결제가 없어 잔액 0 이다.
 * 결제 1 ~ 400 만으로는 300,000 에 못 미쳐 결제 401 부터 승인 결제를 더 넣어 채운다.
 */

-- 숫자 표 1 ~ 1000
CREATE TEMPORARY TABLE base_n (n INT PRIMARY KEY);
INSERT INTO base_n (n)
SELECT a.d + b.d * 10 + c.d * 100 + 1
FROM (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a
CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b
CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) c;

-- 기준일 D
SET @base_d = CAST('${baseDate}' AS DATE);

/*
 * 포인트 계정 (BIL.BALANCE_ZERO 는 점주 196 ~ 205 의 계정 행만 있고 원장 없음). 점주 301 ~ 500 (#858) 도 계정이 있다.
 * 만든 시각은 점주 가입일 D-(120 - n % 110) 10:00 에 1분을 더한 값이다.
 */
INSERT INTO point_account (point_account_id, owner_id, created_at, updated_at)
SELECT 10000000 + n, 10000000 + n,
       DATE_SUB(@base_d, INTERVAL (120 - n % 110) DAY) + INTERVAL 601 MINUTE,
       DATE_SUB(@base_d, INTERVAL (120 - n % 110) DAY) + INTERVAL 601 MINUTE
FROM base_n
WHERE n <= 250 OR n BETWEEN 301 AND 500
ON DUPLICATE KEY UPDATE point_account_id = point_account.point_account_id;

/*
 * 결제 p = 1 ~ 400, 점주 (p - 1) % 250 + 1, 날짜 D-(1 + p * 37 % 90).
 * 상태는 p % 40 이 0 FAILED, 1 CANCELLED, 2 UNKNOWN, 3 REQUESTED, 나머지 APPROVED 다.
 * 점주 196 ~ 205 의 승인 결제(p = 196 ~ 199, 204, 205)는 잔액 0 을 지키려고 FAILED 로 둔다.
 * 금액은 충전 상품 [10000, 30000, 50000, 100000][p % 4] 다.
 * REQUESTED 는 p DIV 40 이 홀수면 승인 시도 중이라 pg_tid 가 있고 짝수면 결제창 전이라 없다.
 * CANCELLED 는 모두 승인 전 취소라 pg_tid, approved_at, pg_cancel_id 가 없다.
 * BIL.PAYMENT_APPROVED_D1, BIL.PG_RECON_D1_MATCH, BIL.PAYMENT_FAILED_CANCELLED, BIL.PAYMENT_HISTORY, BIL.PAYMENTS_ALL_STATUSES
 * BIL.PAYMENT_REQUESTED_NO_TID, BIL.PAYMENT_REQUESTED_WITH_TID, BIL.PAYMENT_UNKNOWN, BIL.PAYMENT_OTHER_OWNER, BIL.BALANCE_CHARGED
 */
INSERT INTO payment (payment_id, owner_id, order_id, pg_tid, amount, status, failure_reason, pg_cancel_id,
                     approved_at, canceled_at, created_at, updated_at)
SELECT 10000000 + x.p,
       10000000 + (x.p - 1) % 250 + 1,
       CONCAT('world-order-', LPAD(x.p, 4, '0')),
       CASE WHEN x.status IN ('APPROVED', 'FAILED', 'UNKNOWN')
                 OR (x.status = 'REQUESTED' AND (x.p DIV 40) % 2 = 1)
            THEN CONCAT('world-tid-', LPAD(x.p, 4, '0')) END,
       ELT(x.p % 4 + 1, 10000, 30000, 50000, 100000),
       x.status,
       IF(x.status = 'FAILED', '카드 한도 초과', NULL),
       NULL,
       IF(x.status = 'APPROVED', x.created_at + INTERVAL 1 MINUTE, NULL),
       IF(x.status = 'CANCELLED', x.created_at + INTERVAL 10 MINUTE, NULL),
       x.created_at,
       CASE x.status WHEN 'APPROVED' THEN x.created_at + INTERVAL 1 MINUTE
                     WHEN 'CANCELLED' THEN x.created_at + INTERVAL 10 MINUTE
                     ELSE x.created_at END
FROM (
    SELECT n AS p,
           CASE WHEN n % 40 = 0 THEN 'FAILED'
                WHEN n % 40 = 1 THEN 'CANCELLED'
                WHEN n % 40 = 2 THEN 'UNKNOWN'
                WHEN n % 40 = 3 THEN 'REQUESTED'
                WHEN n BETWEEN 196 AND 205 THEN 'FAILED'
                ELSE 'APPROVED' END AS status,
           DATE_SUB(@base_d, INTERVAL (1 + n * 37 % 90) DAY) + INTERVAL (540 + n * 13 % 480) MINUTE AS created_at
    FROM base_n
    WHERE n <= 400
) x
ON DUPLICATE KEY UPDATE payment_id = payment.payment_id;

/*
 * 점주마다 승인 충전 합을 300,000 으로 채울 나머지 금액이다.
 * 나머지는 100000, 50000, 10000 상품 순으로 나눈다 (점주마다 최대 8건).
 */
CREATE TEMPORARY TABLE base_rest AS
SELECT o.n,
       (300000 - COALESCE(SUM(pay.amount), 0)) DIV 100000 AS cnt_100k,
       (300000 - COALESCE(SUM(pay.amount), 0)) % 100000 DIV 50000 AS cnt_50k,
       (300000 - COALESCE(SUM(pay.amount), 0)) % 50000 DIV 10000 AS cnt_10k
FROM base_n o
LEFT JOIN payment pay
       ON pay.owner_id = 10000000 + o.n
      AND pay.status = 'APPROVED'
      AND pay.payment_id BETWEEN 10000001 AND 10000400
WHERE o.n <= 250 AND o.n NOT BETWEEN 196 AND 205
GROUP BY o.n;

/*
 * 채움 결제 payment_id = B + 400 + (n - 1) * 10 + j, 날짜 D-(80 + j) 10:00 + n 분.
 * BIL.BALANCE_ENOUGH 는 ACTIVE 캠페인 점주의 잔액 300,000 으로 채운다.
 */
INSERT INTO payment (payment_id, owner_id, order_id, pg_tid, amount, status, failure_reason, pg_cancel_id,
                     approved_at, canceled_at, created_at, updated_at)
SELECT 10000400 + (r.n - 1) * 10 + j.n,
       10000000 + r.n,
       CONCAT('world-order-x', LPAD(r.n, 3, '0'), '-', j.n),
       CONCAT('world-tid-x', LPAD(r.n, 3, '0'), '-', j.n),
       CASE WHEN j.n <= r.cnt_100k THEN 100000
            WHEN j.n <= r.cnt_100k + r.cnt_50k THEN 50000
            ELSE 10000 END,
       'APPROVED', NULL, NULL,
       DATE_SUB(@base_d, INTERVAL (80 + j.n) DAY) + INTERVAL (601 + r.n) MINUTE,
       NULL,
       DATE_SUB(@base_d, INTERVAL (80 + j.n) DAY) + INTERVAL (600 + r.n) MINUTE,
       DATE_SUB(@base_d, INTERVAL (80 + j.n) DAY) + INTERVAL (601 + r.n) MINUTE
FROM base_rest r
JOIN base_n j ON j.n <= r.cnt_100k + r.cnt_50k + r.cnt_10k
ON DUPLICATE KEY UPDATE payment_id = payment.payment_id;

/*
 * BIL.BALANCE_ENOUGH: 진행 중 캠페인을 더한 점주 301 ~ 330 과 341 (#858) 은 100,000 승인 결제 3건으로 300,000 이다.
 * payment_id = B + 3000 + (n - 301) * 3 + j 이고 날짜는 채움 결제와 같은 D-(80 + j) 다.
 */
INSERT INTO payment (payment_id, owner_id, order_id, pg_tid, amount, status, failure_reason, pg_cancel_id,
                     approved_at, canceled_at, created_at, updated_at)
SELECT 10003000 + (o.n - 301) * 3 + j.n,
       10000000 + o.n,
       CONCAT('world-order-y', LPAD(o.n, 3, '0'), '-', j.n),
       CONCAT('world-tid-y', LPAD(o.n, 3, '0'), '-', j.n),
       100000, 'APPROVED', NULL, NULL,
       DATE_SUB(@base_d, INTERVAL (80 + j.n) DAY) + INTERVAL (601 + o.n) MINUTE,
       NULL,
       DATE_SUB(@base_d, INTERVAL (80 + j.n) DAY) + INTERVAL (600 + o.n) MINUTE,
       DATE_SUB(@base_d, INTERVAL (80 + j.n) DAY) + INTERVAL (601 + o.n) MINUTE
FROM base_n o
JOIN (SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3) j
WHERE o.n BETWEEN 301 AND 330 OR o.n = 341
ON DUPLICATE KEY UPDATE payment_id = payment.payment_id;

/*
 * 미확정 결제 재조회 아웃박스, payment_reconciliation_outbox_id = payment_id.
 * BIL.PAYMENT_UNKNOWN_PENDING 은 p = 2, BIL.PAYMENT_UNKNOWN_FAILED_PERMANENT 는 p = 42, BIL.PAYMENT_UNKNOWN_LAST_ATTEMPT 는 p = 82 다.
 * 나머지 UNKNOWN 은 BIL.PAYMENT_UNKNOWN 모양(PENDING, 시도 0)이다.
 * BIL.PAYMENTS_ALL_STATUSES 의 RESOLVED 아웃박스는 승인 결제 p = 4, 5 에 둔다.
 */
INSERT INTO payment_reconciliation_outbox (payment_reconciliation_outbox_id, payment_id, status, attempt_count, max_retry,
                                           next_retry_at, last_checked_at, last_error, created_at, updated_at)
SELECT pay.payment_id,
       pay.payment_id,
       CASE WHEN pay.status = 'APPROVED' THEN 'RESOLVED'
            WHEN pay.payment_id = 10000042 THEN 'FAILED_PERMANENT'
            ELSE 'PENDING' END,
       CASE pay.payment_id WHEN 10000002 THEN 2 WHEN 10000042 THEN 5 WHEN 10000082 THEN 4
            ELSE IF(pay.status = 'APPROVED', 2, 0) END,
       IF(pay.payment_id = 10000082, 4, 5),
       CASE WHEN pay.status = 'APPROVED' OR pay.payment_id = 10000042 THEN NULL
            WHEN pay.payment_id = 10000002 THEN pay.created_at + INTERVAL 10 MINUTE
            WHEN pay.payment_id = 10000082 THEN pay.created_at + INTERVAL 48 MINUTE
            ELSE pay.created_at + INTERVAL 1 MINUTE END,
       CASE WHEN pay.status = 'APPROVED' OR pay.payment_id IN (10000002, 10000042, 10000082)
            THEN pay.created_at + INTERVAL 5 MINUTE END,
       CASE pay.payment_id WHEN 10000002 THEN 'PG 응답 시간 초과' WHEN 10000042 THEN 'NOT_FOUND'
            WHEN 10000082 THEN 'PG 응답 시간 초과' END,
       pay.created_at + INTERVAL 1 MINUTE,
       pay.created_at + INTERVAL 5 MINUTE
FROM payment pay
WHERE (pay.status = 'UNKNOWN' AND pay.payment_id BETWEEN 10000001 AND 10000400)
   OR pay.payment_id IN (10000004, 10000005)
ON DUPLICATE KEY UPDATE payment_reconciliation_outbox_id = payment_reconciliation_outbox.payment_reconciliation_outbox_id;

/*
 * 승인 결제마다 CHARGE 원장, point_ledger_id = payment_id.
 * p = 56, 146 은 대사 배치가 빠진 충전을 D-2 01:31 에 보정 기록한 것이라 source 가 RECONCILIATION 이다 (BIL.MISMATCH_RESOLVED).
 */
INSERT INTO point_ledger (point_ledger_id, owner_id, campaign_id, serve_id, payment_id, refund_request_id, business_date,
                          adjust_target_date, entry_type, source, amount, reason, idempotency_key, created_at, updated_at)
SELECT pay.payment_id, pay.owner_id, NULL, NULL, pay.payment_id, NULL, NULL, NULL,
       'CHARGE',
       IF(pay.payment_id IN (10000056, 10000146), 'RECONCILIATION', 'REALTIME'),
       pay.amount, NULL, NULL,
       IF(pay.payment_id IN (10000056, 10000146), DATE_SUB(@base_d, INTERVAL 2 DAY) + INTERVAL 91 MINUTE, pay.approved_at),
       IF(pay.payment_id IN (10000056, 10000146), DATE_SUB(@base_d, INTERVAL 2 DAY) + INTERVAL 91 MINUTE, pay.approved_at)
FROM payment pay
WHERE pay.status = 'APPROVED' AND pay.payment_id BETWEEN 10000001 AND 10099999
ON DUPLICATE KEY UPDATE point_ledger_id = point_ledger.point_ledger_id;

/*
 * 관리자 조정 ADJUST, point_ledger_id = B + 90000 + k, 모두 MANUAL 이고 멱등 키 world-adjust-<k> 를 가진다.
 * k1 BIL.ADJUST_RECORDED (점주 1, +5000, D 00:10), k2 BIL.ADJUST_IDEM_KEY (점주 2, +1000, D-1).
 * k3 ~ k9 BIL.LEDGER_MIXED 의 조정 7건 (점주 1, + 4건과 - 3건, 2건은 캠페인 1 과 대상일).
 * k10 BIL.MISMATCH_RESOLVED 의 과과금 환급 (점주 2, 캠페인 2, 대상 D-4).
 * k11 BIL.ADJUST_OTHER_DATE 이자 캠페인 7 과다 소진 환급 (대상 D-3, 기록 D-1).
 * k12, k13 BIL.BALANCE_PARTIAL (DRAFT 점주 225, 226 의 잔액을 500 으로).
 * k14 ~ k16 BIL.BALANCE_LOW (DRAFT 점주 222 ~ 224 의 잔액을 10000 으로).
 * k17 BIL.REFUND_REQUESTED_LOW_BALANCE (점주 243, 요청 뒤 D 에 -20000).
 * k18, k19 BIL.LEDGER_STANDALONE (점주 245, +500 과 -300).
 */
INSERT INTO point_ledger (point_ledger_id, owner_id, campaign_id, serve_id, payment_id, refund_request_id, business_date,
                          adjust_target_date, entry_type, source, amount, reason, idempotency_key, created_at, updated_at)
SELECT 10090000 + v.k, 10000000 + v.owner_n,
       IF(v.campaign_n IS NULL, NULL, 10000000 + v.campaign_n),
       NULL, NULL, NULL, NULL,
       IF(v.target_days IS NULL, NULL, DATE_SUB(@base_d, INTERVAL v.target_days DAY)),
       'ADJUST', 'MANUAL', v.amount, v.reason, CONCAT('world-adjust-', v.k),
       DATE_SUB(@base_d, INTERVAL v.days DAY) + INTERVAL v.minutes MINUTE,
       DATE_SUB(@base_d, INTERVAL v.days DAY) + INTERVAL v.minutes MINUTE
FROM (VALUES
    ROW(1, 1, NULL, NULL, 5000, '보상 지급', 0, 10),
    ROW(2, 2, NULL, NULL, 1000, '이벤트 보상', 1, 660),
    ROW(3, 1, NULL, NULL, 1000, '이벤트 보상', 20, 600),
    ROW(4, 1, NULL, NULL, 2000, '장애 보상', 17, 600),
    ROW(5, 1, NULL, NULL, -500, '과오 충전 회수', 14, 600),
    ROW(6, 1, NULL, NULL, -300, '과오 충전 회수', 11, 600),
    ROW(7, 1, NULL, NULL, 300, '이벤트 보상', 8, 600),
    ROW(8, 1, 1, 6, 200, '과과금 환급', 5, 600),
    ROW(9, 1, 1, 4, -100, '과소 차감 정정', 3, 600),
    ROW(10, 2, 2, 4, 100, '과과금 환급', 3, 600),
    ROW(11, 7, 7, 3, 100, '과과금 환급', 1, 600),
    ROW(12, 225, NULL, NULL, -299500, '시나리오 잔액 조정', 3, 600),
    ROW(13, 226, NULL, NULL, -299500, '시나리오 잔액 조정', 3, 600),
    ROW(14, 222, NULL, NULL, -290000, '시나리오 잔액 조정', 10, 600),
    ROW(15, 223, NULL, NULL, -290000, '시나리오 잔액 조정', 10, 600),
    ROW(16, 224, NULL, NULL, -290000, '시나리오 잔액 조정', 10, 600),
    ROW(17, 243, NULL, NULL, -20000, '과오 충전 회수', 0, 20),
    ROW(18, 245, NULL, NULL, 500, '이벤트 보상', 8, 600),
    ROW(19, 245, NULL, NULL, -300, '과오 충전 회수', 7, 600)
) AS v(k, owner_n, campaign_n, target_days, amount, reason, days, minutes)
ON DUPLICATE KEY UPDATE point_ledger_id = point_ledger.point_ledger_id;

/*
 * 환불 요청 refund_request_id = B + r, 처리자는 world-admin-01 (10000001).
 * 금액은 요청 시각의 잔액(DEDUCT 를 뺀 원장 합) 전액이고 점주 5 만 V6 예약을 모르므로 200,000 으로 둔다.
 * r1 점주 243 REJECTED, r2 점주 243 REQUESTED (BIL.REFUND_REQUESTED, BIL.REFUND_REQUESTED_LOW_BALANCE).
 * r3 점주 244 REQUESTED (BIL.REFUND_REQUESTED_APPROVABLE, 결제 244 와 연결).
 * r4 점주 245 COMPLETED (BIL.LEDGER_STANDALONE, BIL.ZERO_BALANCE_IDLE_OWNER).
 * r5 점주 246 REJECTED, r6 점주 246 COMPLETED (BIL.REFUND_PROCESSED, BIL.REFUND_HISTORY).
 * r7 점주 247 COMPLETED (BIL.ZERO_BALANCE_IDLE_OWNER).
 * r8 점주 5 REQUESTED (BIL.REFUND_REQUESTED_ACTIVE_CAMPAIGN).
 * 점주 248 ~ 250 은 요청 없이 잔액 300,000 이다 (BIL.REFUNDABLE_OWNER).
 */
INSERT INTO refund_request (refund_request_id, owner_id, payment_id, amount, fee, status, reason, reject_reason,
                            processed_at, processed_by, created_at, updated_at)
SELECT 10000000 + v.r,
       10000000 + v.owner_n,
       IF(v.payment_p IS NULL, NULL, 10000000 + v.payment_p),
       IF(v.owner_n = 5, 200000,
          (SELECT SUM(l.amount) FROM point_ledger l
           WHERE l.owner_id = 10000000 + v.owner_n
             AND l.entry_type <> 'DEDUCT'
             AND l.created_at < DATE_SUB(@base_d, INTERVAL v.req_days DAY) + INTERVAL v.req_minutes MINUTE)),
       IF(v.status = 'COMPLETED', 0, NULL),
       v.status,
       v.reason,
       IF(v.status = 'REJECTED', '집행 내역 확인 필요', NULL),
       IF(v.status = 'REQUESTED', NULL, DATE_SUB(@base_d, INTERVAL (v.req_days - 1) DAY) + INTERVAL 600 MINUTE),
       IF(v.status = 'REQUESTED', NULL, 10000001),
       DATE_SUB(@base_d, INTERVAL v.req_days DAY) + INTERVAL v.req_minutes MINUTE,
       IF(v.status = 'REQUESTED', DATE_SUB(@base_d, INTERVAL v.req_days DAY) + INTERVAL v.req_minutes MINUTE,
          DATE_SUB(@base_d, INTERVAL (v.req_days - 1) DAY) + INTERVAL 600 MINUTE)
FROM (VALUES
    ROW(1, 243, NULL, 'REJECTED', '운영 중단', 6, 600),
    ROW(2, 243, NULL, 'REQUESTED', '폐업 예정', 1, 600),
    ROW(3, 244, 244, 'REQUESTED', '폐업 예정', 1, 660),
    ROW(4, 245, NULL, 'COMPLETED', '광고 종료', 4, 600),
    ROW(5, 246, NULL, 'REJECTED', '광고 종료', 10, 600),
    ROW(6, 246, NULL, 'COMPLETED', '광고 종료', 4, 660),
    ROW(7, 247, NULL, 'COMPLETED', NULL, 6, 600),
    ROW(8, 5, NULL, 'REQUESTED', '폐업 예정', 1, 720)
) AS v(r, owner_n, payment_p, status, reason, req_days, req_minutes)
ON DUPLICATE KEY UPDATE refund_request_id = refund_request.refund_request_id;

-- 처리 완료 환불마다 REFUND 원장, point_ledger_id = B + 95000 + r, 처리 시각에 기록
INSERT INTO point_ledger (point_ledger_id, owner_id, campaign_id, serve_id, payment_id, refund_request_id, business_date,
                          adjust_target_date, entry_type, source, amount, reason, idempotency_key, created_at, updated_at)
SELECT 10095000 + (rr.refund_request_id - 10000000), rr.owner_id, NULL, NULL, NULL, rr.refund_request_id, NULL, NULL,
       'REFUND', 'MANUAL', -rr.amount, NULL, NULL, rr.processed_at, rr.processed_at
FROM refund_request rr
WHERE rr.status = 'COMPLETED' AND rr.refund_request_id BETWEEN 10000001 AND 10000099
ON DUPLICATE KEY UPDATE point_ledger_id = point_ledger.point_ledger_id;

/*
 * 정산 대사 불일치 3유형 x (해결 2, 미해결 1), settlement_mismatch_id = B + 1 ~ B + 9.
 * job_execution_id 는 배치 실행 기록(V7)의 번호 공식이 없어 비운다.
 * 1 BIL.MISMATCH_UNRESOLVED_PG (결제 270, D-1 승인 50000, PG 45000).
 * 2, 3 BIL.MISMATCH_RESOLVED 의 PG 대사 (결제 56, 146 은 D-3 승인인데 충전이 빠져 D-2 01:31 배치가 보정).
 * 4, 7 BIL.MISMATCH_UNRESOLVED_CAMPAIGN (캠페인 1, D-1).
 * 5 BIL.MISMATCH_RESOLVED 의 관리자 정정 (캠페인 2, D-4, 짝 ADJUST k10).
 * 8 캠페인 7 과다 소진을 관리자가 정정 (D-3, 짝 ADJUST k11).
 * 6, 9 카운터 재구성으로 닫힌 캠페인 불일치.
 */
INSERT INTO settlement_mismatch (settlement_mismatch_id, check_type, business_date, target_id, expected_value, actual_value,
                                 resolution_type, job_execution_id, resolved_at, resolved_by, created_at, updated_at)
SELECT 10000000 + v.id, v.check_type,
       DATE_SUB(@base_d, INTERVAL v.biz_days DAY),
       10000000 + v.target_n, v.expected_value, v.actual_value, v.resolution_type, NULL,
       IF(v.resolution_type IS NULL, NULL, DATE_SUB(@base_d, INTERVAL v.res_days DAY) + INTERVAL v.res_minutes MINUTE),
       IF(v.resolution_type = 'OVERCHARGE_REVERSAL', 10000001, NULL),
       DATE_SUB(@base_d, INTERVAL (v.biz_days - 1) DAY) + INTERVAL v.created_minutes MINUTE,
       IF(v.resolution_type IS NULL, DATE_SUB(@base_d, INTERVAL (v.biz_days - 1) DAY) + INTERVAL v.created_minutes MINUTE,
          DATE_SUB(@base_d, INTERVAL v.res_days DAY) + INTERVAL v.res_minutes MINUTE)
FROM (VALUES
    ROW(1, 'PG_VS_CHARGE', 1, 270, 50000, 45000, NULL, 0, 0, 91),
    ROW(2, 'PG_VS_CHARGE', 3, 56, 10000, 0, 'LEDGER_CORRECTION', 2, 91, 90),
    ROW(3, 'PG_VS_CHARGE', 3, 146, 50000, 0, 'LEDGER_CORRECTION', 2, 91, 90),
    ROW(4, 'CAMPAIGN_SPEND_VS_LEDGER', 1, 1, 1000, 1100, NULL, 0, 0, 5),
    ROW(5, 'CAMPAIGN_SPEND_VS_LEDGER', 4, 2, 1000, 1100, 'OVERCHARGE_REVERSAL', 3, 600, 5),
    ROW(6, 'CAMPAIGN_SPEND_VS_LEDGER', 5, 6, 2000, 1990, 'CACHE_REBUILD', 4, 6, 5),
    ROW(7, 'CAMPAIGN_OVERSPEND', 1, 1, 1000, 1100, NULL, 0, 0, 5),
    ROW(8, 'CAMPAIGN_OVERSPEND', 3, 7, 1000, 1100, 'OVERCHARGE_REVERSAL', 1, 600, 5),
    ROW(9, 'CAMPAIGN_OVERSPEND', 6, 9, 3000, 3010, 'CACHE_REBUILD', 5, 6, 5)
) AS v(id, check_type, biz_days, target_n, expected_value, actual_value, resolution_type, res_days, res_minutes, created_minutes)
ON DUPLICATE KEY UPDATE settlement_mismatch_id = settlement_mismatch.settlement_mismatch_id;

/*
 * 포인트 정책 이력 2행 (BIL.POLICY_HISTORY), 마이그레이션이 넣은 2026-01-01 기본 행은 그대로 둔다.
 * D-30 에 200000 상품을 시범으로 더했다가 D-10 에 기본 상품 구성으로 되돌려 지금 적용 값은 기본 행과 같다.
 */
INSERT INTO point_policy (point_policy_id, min_charge_amount, charge_products, change_reason, effective_date,
                          created_by, created_at, updated_at)
SELECT 10000000 + v.id, 10000,
       IF(v.id = 1, JSON_ARRAY(10000, 30000, 50000, 100000, 200000), JSON_ARRAY(10000, 30000, 50000, 100000)),
       v.change_reason,
       DATE_SUB(@base_d, INTERVAL v.eff_days DAY),
       10000001,
       DATE_SUB(@base_d, INTERVAL (v.eff_days + 1) DAY) + INTERVAL 900 MINUTE,
       DATE_SUB(@base_d, INTERVAL (v.eff_days + 1) DAY) + INTERVAL 900 MINUTE
FROM (VALUES
    ROW(1, '200000 상품 시범 판매', 30),
    ROW(2, '200000 상품 시범 판매 종료', 10)
) AS v(id, change_reason, eff_days)
ON DUPLICATE KEY UPDATE point_policy_id = point_policy.point_policy_id;

DROP TEMPORARY TABLE base_rest;
DROP TEMPORARY TABLE base_n;
