/*
 * 결제, 포인트 원장, 환불 요청, 정산 불일치다. 점주 9013~9015 가 쓴다.
 * 광고 서빙 시드의 노출은 캠페인 9001~9012 에만 닿는다. 그 노출과 짝인 정산은 R__65 가 넣는다.
 * 이 파일의 점주는 노출이 닿지 않는 9013~9015 라서 합계 DEDUCT 행이 노출별 DEDUCT 와 겹쳐 세어지지 않는다.
 * 날짜는 ${seedBusinessDate} 에서 며칠 전으로 잡는다. 이미 들어간 행은 건드리지 않아 날짜가 처음 넣은 날에 멈춘다.
 * 점주 9013 은 확인 중인 결제가 없어 새 충전을 시험할 수 있다. 9014 와 9015 는 UNKNOWN 결제가 있어 새 충전이 막힌다(BILLING-008).
 * 모든 점주가 집행 중 캠페인(9013~9015)을 가져 환불 승인은 BILLING-010 으로 거절된다. 승인을 시험하려면 캠페인을 먼저 멈춘다.
 * 잔액은 DEDUCT 를 뺀 원장 합이다. 9013 은 88200, 9014 는 57000(환불 요청 금액과 같다), 9015 는 10000 이다.
 * 지우려면 PK 9000번대를 point_ledger, payment_reconciliation_outbox 와 refund_request, payment 순서로 지운다.
 */
INSERT INTO point_account (point_account_id, owner_id, created_at, updated_at)
VALUES (9013, 9013, NOW(6), NOW(6)),
       (9014, 9014, NOW(6), NOW(6)),
       (9015, 9015, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE point_account_id = point_account_id;

/*
 * 결제다.
 * APPROVED, FAILED, CANCELLED, UNKNOWN, REQUESTED 를 모두 둔다.
 * 9006 은 승인 시도 전 주문(pg_tid 없음)이라 점주 목록에서 빠지고 관리자 목록에만 나온다.
 */
INSERT INTO payment (payment_id, owner_id, order_id, pg_tid, amount, status, failure_reason, pg_cancel_id,
                     approved_at, canceled_at, created_at, updated_at)
VALUES (9001, 9013, 'seed_ord_9001', 'seed_tid_9001', 100000, 'APPROVED', NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 6 DAY), '10:00:05'), NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 6 DAY), '10:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 6 DAY), '10:00:05')),
       (9002, 9013, 'seed_ord_9002', 'seed_tid_9002', 30000, 'APPROVED', NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '11:00:05'), NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '11:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '11:00:05')),
       (9003, 9013, 'seed_ord_9003', 'seed_tid_9003', 50000, 'FAILED', '카드 한도 초과', NULL,
        NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '14:20:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '14:20:03')),
       (9004, 9013, 'seed_ord_9004', 'seed_tid_9004', 10000, 'CANCELLED', NULL, 'seed_cancel_9004',
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), '09:30:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), '09:45:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), '09:30:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), '09:45:00')),
       (9005, 9015, 'seed_ord_9005', 'seed_tid_9005', 30000, 'UNKNOWN', NULL, NULL,
        NULL, NULL, NOW(6) - INTERVAL 30 MINUTE, NOW(6) - INTERVAL 30 MINUTE),
       (9006, 9013, 'seed_ord_9006', NULL, 10000, 'REQUESTED', NULL, NULL,
        NULL, NULL, NOW(6) - INTERVAL 10 MINUTE, NOW(6) - INTERVAL 10 MINUTE),
       (9007, 9014, 'seed_ord_9007', 'seed_tid_9007', 50000, 'APPROVED', NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 5 DAY), '10:00:05'), NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 5 DAY), '10:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 5 DAY), '10:00:05')),
       (9008, 9014, 'seed_ord_9008', 'seed_tid_9008', 10000, 'UNKNOWN', NULL, NULL,
        NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '16:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '16:30:00')),
       (9009, 9014, 'seed_ord_9009', 'seed_tid_9009', 30000, 'APPROVED', NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), '10:00:05'), NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), '10:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), '10:00:05')),
       (9010, 9015, 'seed_ord_9010', 'seed_tid_9010', 100000, 'APPROVED', NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), '10:00:05'), NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), '10:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), '10:00:05')),
       (9011, 9015, 'seed_ord_9011', 'seed_tid_9011', 10000, 'APPROVED', NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '10:00:05'), NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '10:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '10:00:05')),
       (9012, 9015, 'seed_ord_9012', 'seed_tid_9012', 30000, 'FAILED', 'PG 승인 거절', NULL,
        NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '13:10:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '13:10:02'))
ON DUPLICATE KEY UPDATE payment_id = payment_id;

/*
 * 확인 중(UNKNOWN) 결제의 재조회 아웃박스다.
 * 9005 는 재시도 대기이고 9008 은 재시도를 다 써서 사람이 recheck 해야 하는 건이다.
 */
INSERT INTO payment_reconciliation_outbox (payment_reconciliation_outbox_id, payment_id, status, attempt_count,
                                           max_retry, next_retry_at, last_checked_at, last_error,
                                           created_at, updated_at)
VALUES (9001, 9005, 'PENDING', 2, 5, NOW(6) + INTERVAL 5 MINUTE, NOW(6) - INTERVAL 1 MINUTE, 'PG timeout',
        NOW(6) - INTERVAL 30 MINUTE, NOW(6) - INTERVAL 1 MINUTE),
       (9002, 9008, 'FAILED_PERMANENT', 5, 5, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '18:00:00'), 'PG timeout',
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '16:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '18:00:00'))
ON DUPLICATE KEY UPDATE payment_reconciliation_outbox_id = payment_reconciliation_outbox_id;

/*
 * 환불 요청이다.
 * 9001 은 반려, 9002 는 완료(REFUND 원장 9006), 9003 은 대기이고 금액은 점주 9014 의 잔액 전액이다.
 * 같은 점주의 대기 요청은 하나만 둘 수 있다.
 */
INSERT INTO refund_request (refund_request_id, owner_id, payment_id, amount, fee, status, reason, reject_reason,
                            processed_at, processed_by, created_at, updated_at)
VALUES (9001, 9015, NULL, 100000, 0, 'REJECTED', '서비스 중단', '약관상 환불 불가 기간',
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 6 DAY), '15:00:00'), 9001,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 6 DAY), '10:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 6 DAY), '15:00:00')),
       (9002, 9015, NULL, 100000, 0, 'COMPLETED', '가게 휴업', NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '11:00:00'), 9001,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '10:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '11:00:00')),
       (9003, 9014, NULL, 57000, 0, 'REQUESTED', NULL, NULL,
        NULL, NULL, NOW(6) - INTERVAL 2 HOUR, NOW(6) - INTERVAL 2 HOUR)
ON DUPLICATE KEY UPDATE refund_request_id = refund_request_id;

/*
 * 포인트 원장이다.
 * 점주 9013 은 5일간 RESERVE, DEDUCT, RELEASE 가 이어지고 양수 ADJUST(과오 차감 정정)와 음수 ADJUST 가 하나씩 있다.
 * 점주 9014 는 3일간, 점주 9015 는 충전 후 전액 환불이다.
 * DEDUCT 는 노출마다 한 행이지만 시드는 하루 합계를 한 행으로 둔다. 점주용 목록에는 나오지 않고 관리자 원장에만 나온다.
 * RESERVE 는 00:00 배치, RELEASE 는 13:30 배치가 쓴 행이라 source 가 RECONCILIATION 이다.
 */
INSERT INTO point_ledger (point_ledger_id, owner_id, campaign_id, serve_id, payment_id, refund_request_id,
                          business_date, adjust_target_date, entry_type, source, amount, reason, idempotency_key,
                          created_at, updated_at)
VALUES (9001, 9013, NULL, NULL, 9001, NULL, NULL, NULL, 'CHARGE', 'REALTIME', 100000, NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 6 DAY), '10:00:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 6 DAY), '10:00:05')),
       (9002, 9013, NULL, NULL, 9002, NULL, NULL, NULL, 'CHARGE', 'REALTIME', 30000, NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '11:00:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '11:00:05')),
       (9003, 9014, NULL, NULL, 9007, NULL, NULL, NULL, 'CHARGE', 'REALTIME', 50000, NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 5 DAY), '10:00:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 5 DAY), '10:00:05')),
       (9004, 9014, NULL, NULL, 9009, NULL, NULL, NULL, 'CHARGE', 'REALTIME', 30000, NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), '10:00:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), '10:00:05')),
       (9005, 9015, NULL, NULL, 9010, NULL, NULL, NULL, 'CHARGE', 'REALTIME', 100000, NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), '10:00:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 7 DAY), '10:00:05')),
       (9006, 9015, NULL, NULL, NULL, 9002, NULL, NULL, 'REFUND', 'REALTIME', -100000, NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '11:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '11:00:00')),
       (9007, 9015, NULL, NULL, 9011, NULL, NULL, NULL, 'CHARGE', 'REALTIME', 10000, NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '10:00:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '10:00:05')),
       (9008, 9013, 9013, NULL, NULL, NULL, NULL, DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), 'ADJUST', 'MANUAL', 500,
        '과오 차감 정정', 'seed-adjust-9013-a',
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '15:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '15:00:00')),
       (9009, 9013, NULL, NULL, NULL, NULL, NULL, NULL, 'ADJUST', 'MANUAL', -300,
        '운영 수동 차감', 'seed-adjust-9013-b',
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), '16:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), '16:00:00'))
ON DUPLICATE KEY UPDATE point_ledger_id = point_ledger_id;

/* 점주 9013 의 예약. 하루 10000 이다. */
INSERT INTO point_ledger (point_ledger_id, owner_id, campaign_id, serve_id, payment_id, refund_request_id,
                          business_date, entry_type, source, amount, created_at, updated_at)
VALUES (9011, 9013, 9013, NULL, NULL, NULL, DATE_SUB('${seedBusinessDate}', INTERVAL 5 DAY), 'RESERVE', 'RECONCILIATION', -10000,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 5 DAY), '00:00:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 5 DAY), '00:00:05')),
       (9012, 9013, 9013, NULL, NULL, NULL, DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), 'RESERVE', 'RECONCILIATION', -10000,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '00:00:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '00:00:05')),
       (9013, 9013, 9013, NULL, NULL, NULL, DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), 'RESERVE', 'RECONCILIATION', -10000,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '00:00:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '00:00:05')),
       (9014, 9013, 9013, NULL, NULL, NULL, DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), 'RESERVE', 'RECONCILIATION', -10000,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '00:00:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '00:00:05')),
       (9015, 9013, 9013, NULL, NULL, NULL, DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), 'RESERVE', 'RECONCILIATION', -10000,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), '00:00:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), '00:00:05'))
ON DUPLICATE KEY UPDATE point_ledger_id = point_ledger_id;

/* 점주 9013 의 해제. 예약에서 소진을 뺀 나머지다. */
INSERT INTO point_ledger (point_ledger_id, owner_id, campaign_id, serve_id, payment_id, refund_request_id,
                          business_date, entry_type, source, amount, created_at, updated_at)
VALUES (9021, 9013, 9013, NULL, NULL, NULL, DATE_SUB('${seedBusinessDate}', INTERVAL 5 DAY), 'RELEASE', 'RECONCILIATION', 1200,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 5 DAY), '13:30:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 5 DAY), '13:30:05')),
       (9022, 9013, 9013, NULL, NULL, NULL, DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), 'RELEASE', 'RECONCILIATION', 800,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '13:30:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '13:30:05')),
       (9023, 9013, 9013, NULL, NULL, NULL, DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), 'RELEASE', 'RECONCILIATION', 2500,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '13:30:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '13:30:05')),
       (9024, 9013, 9013, NULL, NULL, NULL, DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), 'RELEASE', 'RECONCILIATION', 500,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '13:30:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '13:30:05')),
       (9025, 9013, 9013, NULL, NULL, NULL, DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), 'RELEASE', 'RECONCILIATION', 3000,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), '13:30:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), '13:30:05'))
ON DUPLICATE KEY UPDATE point_ledger_id = point_ledger_id;

/* 점주 9013 의 소진. 하루 합계 한 행이고 잔액에는 들어가지 않는다. */
INSERT INTO point_ledger (point_ledger_id, owner_id, campaign_id, serve_id, payment_id, refund_request_id,
                          business_date, entry_type, source, amount, created_at, updated_at)
VALUES (9031, 9013, 9013, UNHEX(LPAD('9031', 32, '0')), NULL, NULL, NULL, 'DEDUCT', 'REALTIME', -8800,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 5 DAY), '12:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 5 DAY), '12:00:00')),
       (9032, 9013, 9013, UNHEX(LPAD('9032', 32, '0')), NULL, NULL, NULL, 'DEDUCT', 'REALTIME', -9200,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '12:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '12:00:00')),
       (9033, 9013, 9013, UNHEX(LPAD('9033', 32, '0')), NULL, NULL, NULL, 'DEDUCT', 'REALTIME', -7500,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '12:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '12:00:00')),
       (9034, 9013, 9013, UNHEX(LPAD('9034', 32, '0')), NULL, NULL, NULL, 'DEDUCT', 'REALTIME', -9500,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '12:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '12:00:00')),
       (9035, 9013, 9013, UNHEX(LPAD('9035', 32, '0')), NULL, NULL, NULL, 'DEDUCT', 'REALTIME', -7000,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), '12:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), '12:00:00'))
ON DUPLICATE KEY UPDATE point_ledger_id = point_ledger_id;

/* 점주 9014 의 예약, 해제, 소진. 사흘치다. */
INSERT INTO point_ledger (point_ledger_id, owner_id, campaign_id, serve_id, payment_id, refund_request_id,
                          business_date, entry_type, source, amount, created_at, updated_at)
VALUES (9041, 9014, 9014, NULL, NULL, NULL, DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), 'RESERVE', 'RECONCILIATION', -10000,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '00:00:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '00:00:05')),
       (9042, 9014, 9014, NULL, NULL, NULL, DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), 'RESERVE', 'RECONCILIATION', -10000,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '00:00:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '00:00:05')),
       (9043, 9014, 9014, NULL, NULL, NULL, DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), 'RESERVE', 'RECONCILIATION', -10000,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '00:00:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '00:00:05')),
       (9051, 9014, 9014, NULL, NULL, NULL, DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), 'RELEASE', 'RECONCILIATION', 2000,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '13:30:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '13:30:05')),
       (9052, 9014, 9014, NULL, NULL, NULL, DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), 'RELEASE', 'RECONCILIATION', 1000,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '13:30:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '13:30:05')),
       (9053, 9014, 9014, NULL, NULL, NULL, DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), 'RELEASE', 'RECONCILIATION', 4000,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '13:30:05'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '13:30:05')),
       (9061, 9014, 9014, UNHEX(LPAD('9061', 32, '0')), NULL, NULL, NULL, 'DEDUCT', 'REALTIME', -8000,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '12:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '12:00:00')),
       (9062, 9014, 9014, UNHEX(LPAD('9062', 32, '0')), NULL, NULL, NULL, 'DEDUCT', 'REALTIME', -9000,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '12:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '12:00:00')),
       (9063, 9014, 9014, UNHEX(LPAD('9063', 32, '0')), NULL, NULL, NULL, 'DEDUCT', 'REALTIME', -6000,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '12:00:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '12:00:00'))
ON DUPLICATE KEY UPDATE point_ledger_id = point_ledger_id;

/*
 * 정산 대사 불일치다. 세 종류를 모두 두고 미해결과 해결을 섞었다.
 * 해결 건 중 배치가 닫은 둘(CACHE_REBUILD, LEDGER_CORRECTION)은 resolved_by 가 비고, OVERCHARGE_REVERSAL 은 관리자 ID 가 필수다.
 * 대상 ID 는 PG_VS_CHARGE 가 결제, 나머지가 캠페인이다.
 */
INSERT INTO settlement_mismatch (settlement_mismatch_id, check_type, business_date, target_id, expected_value,
                                 actual_value, resolution_type, job_execution_id, resolved_at, resolved_by,
                                 created_at, updated_at)
VALUES (9001, 'PG_VS_CHARGE', DATE_SUB('${seedBusinessDate}', INTERVAL 1 DAY), 9005, 30000, 0, NULL, NULL, NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 0 DAY), '00:10:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 0 DAY), '00:10:00')),
       (9002, 'PG_VS_CHARGE', DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), 9008, 10000, 0, 'LEDGER_CORRECTION', NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '00:10:00'), NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '00:05:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '00:10:00')),
       (9003, 'CAMPAIGN_SPEND_VS_LEDGER', DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), 9013, 7500, 7490, NULL, NULL, NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '13:35:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '13:35:00')),
       (9004, 'CAMPAIGN_SPEND_VS_LEDGER', DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), 9014, 9100, 9000, 'CACHE_REBUILD', NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '00:05:00'), NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 3 DAY), '13:35:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '00:05:00')),
       (9005, 'CAMPAIGN_OVERSPEND', DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), 9014, 10000, 10200, NULL, NULL, NULL, NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '13:35:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 2 DAY), '13:35:00')),
       (9006, 'CAMPAIGN_OVERSPEND', DATE_SUB('${seedBusinessDate}', INTERVAL 5 DAY), 9013, 10000, 10500, 'OVERCHARGE_REVERSAL', NULL,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '10:00:00'), 9001,
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 5 DAY), '13:35:00'),
        TIMESTAMP(DATE_SUB('${seedBusinessDate}', INTERVAL 4 DAY), '10:00:00'))
ON DUPLICATE KEY UPDATE settlement_mismatch_id = settlement_mismatch_id;
