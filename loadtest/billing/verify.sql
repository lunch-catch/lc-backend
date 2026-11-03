/*
 * 충전 동시성 시험(charge_concurrency.js)이 끝난 뒤 돌린다.
 * 첫 결과는 k6 요약의 approved_payments, approved_points 와 맞대 본다.
 * 둘째 결과의 모든 행이 0 이면 통과다.
 *
 *   docker exec -i lunchcatch-mysql mysql -uroot -proot lunchcatch < loadtest/billing/verify.sql
 */
SET @lo = 40000001;
SET @hi = 40999999;

/*
 * 1. 승인된 결제의 수와 금액을 센다. 결제를 상태별로도 센다.
 * DB 의 승인 수가 k6 보다 크면 서버는 승인했는데 k6 가 응답을 받지 못한 요청이 있었다는 뜻이다(시간 초과 등).
 * 그 차이는 유실이 아니다. 유실은 아래 2의 첫 행이 센다.
 */
SELECT COUNT(*) AS approved_payments, COALESCE(SUM(amount), 0) AS approved_points
FROM payment
WHERE owner_id BETWEEN @lo AND @hi AND status = 'APPROVED';

SELECT status, COUNT(*) AS payments
FROM payment
WHERE owner_id BETWEEN @lo AND @hi
GROUP BY status;

-- 2. 결과가 0 이어야 하는 점검을 돌린다
SELECT 'approved_without_charge: 승인됐는데 충전이 없다(유실)' AS check_name, COUNT(*) AS violations
FROM payment p
LEFT JOIN point_ledger l ON l.payment_id = p.payment_id AND l.entry_type = 'CHARGE'
WHERE p.owner_id BETWEEN @lo AND @hi AND p.status = 'APPROVED' AND l.point_ledger_id IS NULL
UNION ALL
SELECT 'charge_without_approval: 충전이 있는데 결제가 승인이 아니다', COUNT(*)
FROM point_ledger l
JOIN payment p ON p.payment_id = l.payment_id
WHERE l.owner_id BETWEEN @lo AND @hi AND l.entry_type = 'CHARGE' AND p.status <> 'APPROVED'
UNION ALL
SELECT 'double_charge: 결제 하나에 충전이 둘 이상이다', COUNT(*)
FROM (
    SELECT payment_id
    FROM point_ledger
    WHERE owner_id BETWEEN @lo AND @hi AND entry_type = 'CHARGE'
    GROUP BY payment_id
    HAVING COUNT(*) > 1
) d
UNION ALL
SELECT 'charge_mismatch: 충전의 금액이나 점주가 결제와 다르다', COUNT(*)
FROM point_ledger l
JOIN payment p ON p.payment_id = l.payment_id
WHERE l.owner_id BETWEEN @lo AND @hi AND l.entry_type = 'CHARGE'
  AND (l.amount <> p.amount OR l.owner_id <> p.owner_id)
UNION ALL
SELECT 'stuck_attempt: 승인을 시도했는데 결과가 기록되지 않았다', COUNT(*)
FROM payment
WHERE owner_id BETWEEN @lo AND @hi AND status = 'REQUESTED' AND pg_tid IS NOT NULL
UNION ALL
SELECT 'missing_account: 충전이 있는데 점주 계정 행이 없다', COUNT(DISTINCT l.owner_id)
FROM point_ledger l
LEFT JOIN point_account a ON a.owner_id = l.owner_id
WHERE l.owner_id BETWEEN @lo AND @hi AND l.entry_type = 'CHARGE' AND a.point_account_id IS NULL
UNION ALL
-- 시험 점주에게는 캠페인이 없어 원장에 충전만 남으므로 잔액이 승인된 결제 합과 같아야 한다
SELECT 'balance_mismatch: 점주 잔액이 승인된 결제 합과 다르다', COUNT(*)
FROM (
    SELECT o.owner_id,
           (SELECT COALESCE(SUM(amount), 0) FROM point_ledger
            WHERE owner_id = o.owner_id AND entry_type <> 'DEDUCT') AS balance,
           (SELECT COALESCE(SUM(amount), 0) FROM payment
            WHERE owner_id = o.owner_id AND status = 'APPROVED') AS approved
    FROM owner o
    WHERE o.owner_id BETWEEN @lo AND @hi
) b
WHERE b.balance <> b.approved
UNION ALL
-- 승인은 충전과 재예약 대기 행을 한 트랜잭션에서 남긴다
SELECT 'approved_without_reservation: 승인됐는데 재예약 대기 행이 없다', COUNT(*)
FROM payment p
LEFT JOIN recharge_reservation_outbox r ON r.payment_id = p.payment_id
WHERE p.owner_id BETWEEN @lo AND @hi AND p.status = 'APPROVED' AND r.recharge_reservation_outbox_id IS NULL
UNION ALL
-- k6 직후에는 남을 수 있다. 워커가 처리한 뒤 0 이어야 한다
SELECT 'reservation_pending: 재예약 대기 행이 아직 처리되지 않았다', COUNT(*)
FROM recharge_reservation_outbox r
JOIN payment p ON p.payment_id = r.payment_id
WHERE p.owner_id BETWEEN @lo AND @hi AND r.status <> 'DONE';
