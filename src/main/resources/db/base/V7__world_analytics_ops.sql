/*
 * 기본 세계의 분석과 운영 표를 넣는다 (#741). 캠페인 리포트, daily_* 지표, event_log, batch_execution_log 다.
 * 번호는 B = 10000000 을 더한 고정 번호이고 값은 WORLD_SPEC 의 공식으로 계산한다.
 * D-60 ~ D-8 은 원천이 없어 공식으로 만든다.
 * D-7 ~ D-1 (리포트는 D 까지)은 V6 원천 표를 모아 만든다. V6 가 없으면 그 날짜의 캠페인 행은 0 행이고 플랫폼 일별 행은 0 값으로 남는다.
 * 실제 데이터와 섞이지 않게 원천은 기본 세계 번호대(캠페인 B+1 ~ B+1300, 회원 B+1 ~ B+3000, 점주 B+1 ~ B+300)만 읽는다.
 * 번호 공식: 리포트 id = B + c * 100 + (60 - k), batch id = B + j * 100 + (60 - k). c 는 캠페인 번호, k 는 D 에서 거꾸로 센 날수, j 는 base_job 의 작업 번호다.
 */

SET @d = DATE('${baseDate}');
SET @b = 10000000;

-- 1 ~ 3000 숫자 표
CREATE TEMPORARY TABLE base_n (n INT PRIMARY KEY);

INSERT INTO base_n (n)
SELECT d1.d * 1000 + d2.d * 100 + d3.d * 10 + d4.d
FROM (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d1
CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d2
CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d3
CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d4
WHERE d1.d * 1000 + d2.d * 100 + d3.d * 10 + d4.d BETWEEN 1 AND 3000;

/*
 * 0 ~ 60 작은 숫자 표다. 날수 k, 시간대, 사유 번호처럼 작은 열거에 쓴다.
 * MySQL 은 한 문장에서 같은 임시 표를 두 번 열지 못해 base_n 과 따로 둔다.
 */
CREATE TEMPORARY TABLE base_day (k INT PRIMARY KEY);

INSERT INTO base_day (k)
SELECT n - 1 FROM base_n WHERE n <= 61;

-- 캠페인 리포트 원천 (공식, D-60 ~ D-8)

-- 공식 리포트를 만들 캠페인과 날짜이고 w 는 캠페인 기간 안에서 몇째 날인지(0 부터)다
CREATE TEMPORARY TABLE base_cday (
    c INT NOT NULL,
    k INT NOT NULL,
    w INT NOT NULL,
    h BIGINT NOT NULL,
    PRIMARY KEY (c, k)
);

-- CMP.ACTIVE: 캠페인 1 ~ 180 은 기간 D-14 ~ D+14 라 D-14 ~ D-8 이 공식 범위이고 D 에 시작하는 179 는 뺀다
INSERT INTO base_cday (c, k, w, h)
SELECT n.n, dd.k, 14 - dd.k, CRC32(CONCAT('cdr-', n.n, '-', dd.k))
FROM base_n n
CROSS JOIN base_day dd
WHERE n.n BETWEEN 1 AND 180 AND n.n <> 179 AND dd.k BETWEEN 8 AND 14;

-- CMP.ACTIVE_PAST_END: 캠페인 242 는 D-10 에 시작해 D-10 ~ D-8 이 공식 범위다
INSERT INTO base_cday (c, k, w, h)
SELECT 242, k, 10 - k, CRC32(CONCAT('cdr-', 242, '-', k))
FROM base_day
WHERE k BETWEEN 8 AND 10;

-- CMP.ENDED: 캠페인 1000 + n 은 D-(20 + n % 60) 부터 7일이고 D-60 보다 앞선 날은 자른다
INSERT INTO base_cday (c, k, w, h)
SELECT 1000 + n.n, 20 + n.n % 60 - dd.k, dd.k, CRC32(CONCAT('cdr-', 1000 + n.n, '-', 20 + n.n % 60 - dd.k))
FROM base_n n
CROSS JOIN base_day dd
WHERE n.n BETWEEN 1 AND 300 AND dd.k BETWEEN 0 AND 6 AND 20 + n.n % 60 - dd.k BETWEEN 8 AND 60;

/*
 * 캠페인 x 날짜 리포트 값을 모은다. 공식 행(src F)과 원천 행(src R)이 같은 모양이라 뒤의 표를 한 번에 만든다.
 * imp_valid 는 광고 서빙이 센 유효 노출이고 나머지 노출 수는 과금 판정과 사유별 수다.
 */
CREATE TEMPORARY TABLE base_rpt (
    c INT NOT NULL,
    k INT NOT NULL,
    src CHAR(1) NOT NULL,
    daily_budget BIGINT NOT NULL,
    unit_cost BIGINT NOT NULL,
    charged BIGINT NOT NULL,
    refunded BIGINT NOT NULL,
    billable BIGINT NOT NULL,
    nb_budget BIGINT NOT NULL,
    nb_paused BIGINT NOT NULL,
    nb_no_plan BIGINT NOT NULL,
    nb_sold_out BIGINT NOT NULL,
    inv_expired BIGINT NOT NULL,
    inv_not_owner BIGINT NOT NULL,
    imp_valid BIGINT NOT NULL,
    wish BIGINT NOT NULL,
    issued BIGINT NOT NULL,
    redeem BIGINT NOT NULL,
    distinct_members BIGINT NOT NULL,
    top_members BIGINT NOT NULL,
    PRIMARY KEY (c, k)
);

/*
 * 공식 행이다. 캠페인마다 모양(prof)을 정한다.
 * ANA.FRAUD_CAMPAIGN_WARNINGS: ENDED 1 ~ 10, 61 ~ 70 은 비율 경고(기간 합 유효 약 350, 무효 약 150), 121 ~ 125 는 첫날 집중 경고(유효 200, 상위 5명 150, 회원 8명), 126 은 유효 0(무효 150), 127 은 첫날 유효 1000 무효 10 으로 경고 없음.
 * ANA.REPORT_NO_REDEEM: ENDED n % 60 = 0 (기간 D-20 ~ D-14) 은 사용 0 이다.
 * 나머지는 하루 유효 150 ~ 400, 무효 0 ~ 8 이라 경고가 나지 않는다.
 */
INSERT INTO base_rpt (c, k, src, daily_budget, unit_cost, charged, refunded, billable, nb_budget, nb_paused,
                      nb_no_plan, nb_sold_out, inv_expired, inv_not_owner, imp_valid, wish, issued, redeem,
                      distinct_members, top_members)
SELECT c, k, 'F',
       ELT(c % 4 + 1, 5000, 10000, 20000, 30000),
       10,
       (valid - not_billed) * 10,
       0,
       valid - not_billed,
       not_billed, 0, 0, 0,
       IF((h DIV 7) % 2 = 0, invalid, 0),
       IF((h DIV 7) % 2 = 1, invalid, 0),
       valid,
       FLOOR(valid * (6 + h % 5) / 100),
       FLOOR(FLOOR(valid * (6 + h % 5) / 100) * 3 / 5),
       IF(c > 1000 AND (c - 1000) % 60 = 0, 0, FLOOR(FLOOR(FLOOR(valid * (6 + h % 5) / 100) * 3 / 5) * 2 / 5)),
       CASE WHEN prof = 'CONC' AND w = 0 THEN 8 ELSE FLOOR(valid * 4 / 5) END,
       CASE WHEN prof = 'CONC' AND w = 0 THEN 150 ELSE LEAST(valid, 10 + h % 6) END
FROM (
    SELECT c, k, w, h, prof,
           CASE prof
               WHEN 'RATIO' THEN 40 + h % 21
               WHEN 'CONC' THEN IF(w = 0, 200, 150 + h % 251)
               WHEN 'ZERO' THEN 0
               WHEN 'CLEAN' THEN IF(w = 0, 1000, 150 + h % 251)
               ELSE 150 + h % 251
           END AS valid,
           CASE prof
               WHEN 'RATIO' THEN 18 + h % 8
               WHEN 'CONC' THEN IF(w = 0, 0, h % 9)
               WHEN 'ZERO' THEN IF(w = 0, 150, 0)
               WHEN 'CLEAN' THEN IF(w = 0, 10, h % 9)
               ELSE h % 9
           END AS invalid,
           CASE
               WHEN prof IN ('RATIO', 'ZERO') THEN 0
               WHEN prof IN ('CONC', 'CLEAN') AND w = 0 THEN 0
               ELSE h % 7
           END AS not_billed
    FROM (
        SELECT c, k, w, h,
               CASE
                   WHEN c - 1000 BETWEEN 1 AND 10 OR c - 1000 BETWEEN 61 AND 70 THEN 'RATIO'
                   WHEN c - 1000 BETWEEN 121 AND 125 THEN 'CONC'
                   WHEN c = 1126 THEN 'ZERO'
                   WHEN c = 1127 THEN 'CLEAN'
                   ELSE 'NORMAL'
               END AS prof
        FROM base_cday
    ) profiled
) valued;

-- 캠페인 리포트 원천 (V6 원천, D-7 ~ D)

/*
 * ANA.CAMPAIGN_DAILY_REPORT_D7, ANA.REPORT_DAYS, OPS.AGG_SUCCESS_D2: 집계 작업(CampaignReportStep)과 같은 규칙으로 센다.
 * 대상은 그날 예산 계획, 노출, 과금 판정 중 하나라도 있는 캠페인이다.
 * 과금액은 BILLED 판정의 단가 합(= DEDUCT 합), 정정액은 캠페인과 대상일이 있는 양수 ADJUST 합이다.
 * 찜은 wishlist 생성일, 발급과 사용은 그날 회차(coupon_event)의 member_coupon 으로 센다.
 */
INSERT INTO base_rpt (c, k, src, daily_budget, unit_cost, charged, refunded, billable, nb_budget, nb_paused,
                      nb_no_plan, nb_sold_out, inv_expired, inv_not_owner, imp_valid, wish, issued, redeem,
                      distinct_members, top_members)
SELECT keyed.campaign_id - @b,
       DATEDIFF(@d, keyed.business_date),
       'R',
       COALESCE(plan.daily_budget, 0),
       COALESCE(plan.unit_price, 0),
       COALESCE(judged.charged, 0),
       COALESCE(adjusted.refunded, 0),
       COALESCE(judged.billed, 0),
       COALESCE(judged.nb_budget, 0),
       COALESCE(judged.nb_paused, 0),
       COALESCE(judged.nb_no_plan, 0),
       COALESCE(shown.sold_out, 0),
       COALESCE(shown.inv_expired, 0),
       COALESCE(shown.inv_not_owner, 0),
       COALESCE(shown.valid, 0),
       COALESCE(wished.wish, 0),
       COALESCE(coupons.issued, 0),
       COALESCE(coupons.used, 0),
       COALESCE(shown.distinct_members, 0),
       LEAST(COALESCE(topped.top_members, 0), COALESCE(shown.valid, 0))
FROM (
    SELECT campaign_id, business_date FROM budget_daily_plan
    WHERE business_date BETWEEN DATE_SUB(@d, INTERVAL 7 DAY) AND @d AND campaign_id BETWEEN @b + 1 AND @b + 1300
    UNION
    SELECT campaign_id, business_date FROM impression_log
    WHERE business_date BETWEEN DATE_SUB(@d, INTERVAL 7 DAY) AND @d AND campaign_id BETWEEN @b + 1 AND @b + 1300
    UNION
    SELECT campaign_id, business_date FROM deduction_result
    WHERE business_date BETWEEN DATE_SUB(@d, INTERVAL 7 DAY) AND @d AND campaign_id BETWEEN @b + 1 AND @b + 1300
) keyed
LEFT JOIN budget_daily_plan plan
       ON plan.campaign_id = keyed.campaign_id AND plan.business_date = keyed.business_date
LEFT JOIN (
    SELECT campaign_id, business_date,
           SUM(billing_status = 'BILLED') AS billed,
           SUM(IF(billing_status = 'BILLED', unit_price, 0)) AS charged,
           SUM(billing_status = 'NOT_BILLED_BUDGET') AS nb_budget,
           SUM(billing_status = 'NOT_BILLED_PAUSED') AS nb_paused,
           SUM(billing_status = 'NOT_BILLED_NO_PLAN') AS nb_no_plan
    FROM deduction_result
    WHERE business_date BETWEEN DATE_SUB(@d, INTERVAL 7 DAY) AND @d
    GROUP BY campaign_id, business_date
) judged ON judged.campaign_id = keyed.campaign_id AND judged.business_date = keyed.business_date
LEFT JOIN (
    SELECT campaign_id, business_date,
           SUM(validity = 'VALID') AS valid,
           SUM(validity = 'VALID' AND sold_out = 1) AS sold_out,
           SUM(validity = 'INVALID' AND invalid_reason = 'EXPIRED_OR_UNKNOWN') AS inv_expired,
           SUM(validity = 'INVALID' AND invalid_reason = 'NOT_OWNER') AS inv_not_owner,
           COUNT(DISTINCT IF(validity = 'VALID', member_id, NULL)) AS distinct_members
    FROM impression_log
    WHERE business_date BETWEEN DATE_SUB(@d, INTERVAL 7 DAY) AND @d
    GROUP BY campaign_id, business_date
) shown ON shown.campaign_id = keyed.campaign_id AND shown.business_date = keyed.business_date
LEFT JOIN (
    SELECT campaign_id, business_date, SUM(member_valid) AS top_members
    FROM (
        SELECT campaign_id, business_date, COUNT(*) AS member_valid,
               ROW_NUMBER() OVER (PARTITION BY campaign_id, business_date ORDER BY COUNT(*) DESC, member_id) AS rn
        FROM impression_log
        WHERE business_date BETWEEN DATE_SUB(@d, INTERVAL 7 DAY) AND @d AND validity = 'VALID'
        GROUP BY campaign_id, business_date, member_id
    ) ranked
    WHERE rn <= 5
    GROUP BY campaign_id, business_date
) topped ON topped.campaign_id = keyed.campaign_id AND topped.business_date = keyed.business_date
LEFT JOIN (
    SELECT campaign_id, DATE(created_at) AS business_date, COUNT(*) AS wish
    FROM wishlist
    WHERE created_at >= DATE_SUB(@d, INTERVAL 7 DAY) AND created_at < DATE_ADD(@d, INTERVAL 1 DAY)
    GROUP BY campaign_id, DATE(created_at)
) wished ON wished.campaign_id = keyed.campaign_id AND wished.business_date = keyed.business_date
LEFT JOIN (
    SELECT ce.campaign_id, ce.business_date, COUNT(*) AS issued, SUM(mc.status = 'USED') AS used
    FROM member_coupon mc
    JOIN coupon_event ce ON ce.coupon_event_id = mc.coupon_event_id
    WHERE ce.business_date BETWEEN DATE_SUB(@d, INTERVAL 7 DAY) AND @d
    GROUP BY ce.campaign_id, ce.business_date
) coupons ON coupons.campaign_id = keyed.campaign_id AND coupons.business_date = keyed.business_date
LEFT JOIN (
    SELECT campaign_id, adjust_target_date AS business_date, SUM(amount) AS refunded
    FROM point_ledger
    WHERE entry_type = 'ADJUST' AND amount > 0 AND campaign_id IS NOT NULL
      AND adjust_target_date BETWEEN DATE_SUB(@d, INTERVAL 7 DAY) AND @d
    GROUP BY campaign_id, adjust_target_date
) adjusted ON adjusted.campaign_id = keyed.campaign_id AND adjusted.business_date = keyed.business_date;

-- campaign_daily_report

/*
 * ANA.CAMPAIGN_DAILY_REPORT_D7, ANA.CAMPAIGN_REPORT_30D, ANA.REPORT_DAYS, ANA.REPORT_NO_REDEEM, OPS.AGG_SUCCESS_D2
 * D-1 까지는 FINALIZED 이고 확정 시각은 다음 날 01:05 다. D 는 AGGREGATING 이다.
 * 유효 = 과금 + 미과금 (chk_campaign_daily_report_impression). 가게와 점주는 캠페인의 가게 번호 n 으로 B + n 이다.
 */
INSERT INTO campaign_daily_report (
    campaign_daily_report_id, campaign_id, owner_id, store_id, business_date, daily_budget, charged_amount,
    refunded_amount, spent_amount, budget_spend_rate, impression_unit_cost, valid_impression_count,
    billable_impression_count, not_billed_impression_count, invalid_impression_count, wish_count,
    issued_count, redeem_count, report_status, finalized_at, created_at, updated_at)
SELECT @b + c * 100 + (60 - k),
       @b + c,
       @b + IF(c > 1000, (c - 1001) % 250 + 1, c),
       @b + IF(c > 1000, (c - 1001) % 250 + 1, c),
       DATE_SUB(@d, INTERVAL k DAY),
       daily_budget,
       charged,
       refunded,
       charged - refunded,
       IF(daily_budget = 0, 0, ROUND((charged - refunded) / daily_budget, 6)),
       unit_cost,
       billable + nb_budget + nb_paused + nb_no_plan + nb_sold_out,
       billable,
       nb_budget + nb_paused + nb_no_plan + nb_sold_out,
       inv_expired + inv_not_owner,
       wish,
       issued,
       redeem,
       IF(k = 0, 'AGGREGATING', 'FINALIZED'),
       IF(k = 0, NULL, TIMESTAMP(DATE_SUB(@d, INTERVAL k - 1 DAY), '01:05:00')),
       IF(k = 0, TIMESTAMP(@d, '00:10:00'), TIMESTAMP(DATE_SUB(@d, INTERVAL k - 1 DAY), '01:03:00')),
       IF(k = 0, TIMESTAMP(@d, '00:10:00'), TIMESTAMP(DATE_SUB(@d, INTERVAL k - 1 DAY), '01:05:00'))
FROM base_rpt
ON DUPLICATE KEY UPDATE campaign_daily_report_id = campaign_daily_report_id;

/*
 * 사유별 노출이다. base_day 의 k 0 ~ 5 를 사유 번호로 쓴다.
 * 0 ~ 3 은 미과금(UNBILLED), 4 ~ 5 는 무효(INVALID)다. 0 보다 큰 것만 넣는다 (집계 작업과 같다).
 */
INSERT INTO campaign_daily_impression_reason_report (
    campaign_daily_impression_reason_report_id, campaign_daily_report_id, impression_type, reason_code,
    impression_count, created_at, updated_at)
SELECT @b + (r.c * 100 + 60 - r.k) * 6 + x.k,
       @b + r.c * 100 + (60 - r.k),
       IF(x.k < 4, 'UNBILLED', 'INVALID'),
       ELT(x.k + 1, 'NOT_BILLED_BUDGET', 'NOT_BILLED_PAUSED', 'NOT_BILLED_NO_PLAN', 'NOT_BILLED_SOLD_OUT',
           'EXPIRED_OR_UNKNOWN', 'NOT_OWNER'),
       ELT(x.k + 1, r.nb_budget, r.nb_paused, r.nb_no_plan, r.nb_sold_out, r.inv_expired, r.inv_not_owner),
       IF(r.k = 0, TIMESTAMP(@d, '00:10:00'), TIMESTAMP(DATE_SUB(@d, INTERVAL r.k - 1 DAY), '01:03:00')),
       IF(r.k = 0, TIMESTAMP(@d, '00:10:00'), TIMESTAMP(DATE_SUB(@d, INTERVAL r.k - 1 DAY), '01:03:00'))
FROM base_rpt r
CROSS JOIN base_day x
WHERE x.k BETWEEN 0 AND 5
  AND ELT(x.k + 1, r.nb_budget, r.nb_paused, r.nb_no_plan, r.nb_sold_out, r.inv_expired, r.inv_not_owner) > 0
ON DUPLICATE KEY UPDATE campaign_daily_impression_reason_report_id = campaign_daily_impression_reason_report_id;

-- 시간대별 노출 (공식): 쿠폰 사용 시간대 11 ~ 14 시에 유효 노출을 30, 40, 20, 나머지 비율로 나눈다
INSERT INTO campaign_hourly_report (
    campaign_hourly_report_id, campaign_daily_report_id, report_hour, impression_count, created_at, updated_at)
SELECT @b + (r.c * 100 + 60 - r.k) * 24 + x.k,
       @b + r.c * 100 + (60 - r.k),
       x.k,
       CASE x.k
           WHEN 11 THEN FLOOR(r.imp_valid * 3 / 10)
           WHEN 12 THEN FLOOR(r.imp_valid * 4 / 10)
           WHEN 13 THEN FLOOR(r.imp_valid * 2 / 10)
           ELSE r.imp_valid - FLOOR(r.imp_valid * 3 / 10) - FLOOR(r.imp_valid * 4 / 10) - FLOOR(r.imp_valid * 2 / 10)
       END,
       TIMESTAMP(DATE_SUB(@d, INTERVAL r.k - 1 DAY), '01:03:00'),
       TIMESTAMP(DATE_SUB(@d, INTERVAL r.k - 1 DAY), '01:03:00')
FROM base_rpt r
CROSS JOIN base_day x
WHERE r.src = 'F' AND r.imp_valid > 0 AND x.k BETWEEN 11 AND 14
  AND CASE x.k
          WHEN 11 THEN FLOOR(r.imp_valid * 3 / 10)
          WHEN 12 THEN FLOOR(r.imp_valid * 4 / 10)
          WHEN 13 THEN FLOOR(r.imp_valid * 2 / 10)
          ELSE r.imp_valid - FLOOR(r.imp_valid * 3 / 10) - FLOOR(r.imp_valid * 4 / 10) - FLOOR(r.imp_valid * 2 / 10)
      END > 0
ON DUPLICATE KEY UPDATE campaign_hourly_report_id = campaign_hourly_report_id;

-- 시간대별 노출 (원천): 유효 노출을 표시 시각의 시로 센다
INSERT INTO campaign_hourly_report (
    campaign_hourly_report_id, campaign_daily_report_id, report_hour, impression_count, created_at, updated_at)
SELECT @b + (report.campaign_daily_report_id - @b) * 24 + hourly.report_hour,
       report.campaign_daily_report_id,
       hourly.report_hour,
       hourly.impression_count,
       report.created_at,
       report.created_at
FROM (
    SELECT campaign_id, business_date, HOUR(displayed_at) AS report_hour, COUNT(*) AS impression_count
    FROM impression_log
    WHERE business_date BETWEEN DATE_SUB(@d, INTERVAL 7 DAY) AND @d AND validity = 'VALID'
      AND campaign_id BETWEEN @b + 1 AND @b + 1300
    GROUP BY campaign_id, business_date, HOUR(displayed_at)
) hourly
JOIN campaign_daily_report report
  ON report.campaign_id = hourly.campaign_id AND report.business_date = hourly.business_date
ON DUPLICATE KEY UPDATE campaign_hourly_report_id = campaign_hourly_report_id;

-- 슬롯별 노출 (공식): 배분 슬롯 60 %, 관련성 슬롯 나머지
INSERT INTO campaign_daily_slot_report (
    campaign_daily_slot_report_id, campaign_daily_report_id, slot_type, impression_count, created_at, updated_at)
SELECT @b + (r.c * 100 + 60 - r.k) * 2 + x.k,
       @b + r.c * 100 + (60 - r.k),
       IF(x.k = 0, 'ALLOCATION', 'RELEVANCE'),
       IF(x.k = 0, FLOOR(r.imp_valid * 3 / 5), r.imp_valid - FLOOR(r.imp_valid * 3 / 5)),
       TIMESTAMP(DATE_SUB(@d, INTERVAL r.k - 1 DAY), '01:03:00'),
       TIMESTAMP(DATE_SUB(@d, INTERVAL r.k - 1 DAY), '01:03:00')
FROM base_rpt r
CROSS JOIN base_day x
WHERE r.src = 'F' AND x.k BETWEEN 0 AND 1
  AND IF(x.k = 0, FLOOR(r.imp_valid * 3 / 5), r.imp_valid - FLOOR(r.imp_valid * 3 / 5)) > 0
ON DUPLICATE KEY UPDATE campaign_daily_slot_report_id = campaign_daily_slot_report_id;

-- 슬롯별 노출 (원천): 유효 노출을 slot_type 으로 센다
INSERT INTO campaign_daily_slot_report (
    campaign_daily_slot_report_id, campaign_daily_report_id, slot_type, impression_count, created_at, updated_at)
SELECT @b + (report.campaign_daily_report_id - @b) * 2 + IF(slotted.slot_name = 'ALLOCATION', 0, 1),
       report.campaign_daily_report_id,
       slotted.slot_name,
       slotted.impression_count,
       report.created_at,
       report.created_at
FROM (
    SELECT campaign_id, business_date, CAST(slot_type AS CHAR) AS slot_name, COUNT(*) AS impression_count
    FROM impression_log
    WHERE business_date BETWEEN DATE_SUB(@d, INTERVAL 7 DAY) AND @d AND validity = 'VALID'
      AND slot_type IS NOT NULL AND campaign_id BETWEEN @b + 1 AND @b + 1300
    GROUP BY campaign_id, business_date, slot_type
) slotted
JOIN campaign_daily_report report
  ON report.campaign_id = slotted.campaign_id AND report.business_date = slotted.business_date
ON DUPLICATE KEY UPDATE campaign_daily_slot_report_id = campaign_daily_slot_report_id;

-- 캠페인 단위 일별 지표 (D-60 ~ D-1)

-- 미과금 사유별 노출이고 값은 리포트의 사유 수와 같다
INSERT INTO daily_campaign_not_billed_analytics (
    business_date, campaign_id, not_billed_reason, not_billed_count, created_at, updated_at)
SELECT DATE_SUB(@d, INTERVAL r.k DAY),
       @b + r.c,
       ELT(x.k + 1, 'NOT_BILLED_BUDGET', 'NOT_BILLED_PAUSED', 'NOT_BILLED_NO_PLAN', 'NOT_BILLED_SOLD_OUT'),
       ELT(x.k + 1, r.nb_budget, r.nb_paused, r.nb_no_plan, r.nb_sold_out),
       TIMESTAMP(DATE_SUB(@d, INTERVAL r.k - 1 DAY), '01:03:00'),
       TIMESTAMP(DATE_SUB(@d, INTERVAL r.k - 1 DAY), '01:03:00')
FROM base_rpt r
CROSS JOIN base_day x
WHERE r.k >= 1 AND x.k BETWEEN 0 AND 3
  AND ELT(x.k + 1, r.nb_budget, r.nb_paused, r.nb_no_plan, r.nb_sold_out) > 0
ON DUPLICATE KEY UPDATE daily_campaign_not_billed_analytics.business_date = daily_campaign_not_billed_analytics.business_date;

/*
 * ANA.FRAUD_CAMPAIGN_WARNINGS
 * 유효나 무효가 하나라도 있는 캠페인 x 날짜마다 한 행이다. 집중 의심은 하루 유효 100 이상이고 상위 5명 점유율이 0.5 를 넘을 때다.
 */
INSERT INTO daily_campaign_fraud_analytics (
    business_date, campaign_id, valid_count, invalid_count, distinct_member_count, top_members_count,
    concentration_suspected, created_at, updated_at)
SELECT DATE_SUB(@d, INTERVAL k DAY),
       @b + c,
       imp_valid,
       inv_expired + inv_not_owner,
       distinct_members,
       LEAST(top_members, imp_valid),
       IF(imp_valid >= 100 AND LEAST(top_members, imp_valid) * 2 > imp_valid, 1, 0),
       TIMESTAMP(DATE_SUB(@d, INTERVAL k - 1 DAY), '01:03:00'),
       TIMESTAMP(DATE_SUB(@d, INTERVAL k - 1 DAY), '01:03:00')
FROM base_rpt
WHERE k >= 1 AND imp_valid + inv_expired + inv_not_owner > 0
ON DUPLICATE KEY UPDATE daily_campaign_fraud_analytics.business_date = daily_campaign_fraud_analytics.business_date;

/*
 * ANA.FRAUD_INVALID_D30 (공식, D-60 ~ D-8)
 * 리포트의 무효 노출을 회원 한 명에게 몰아 (날짜, 캠페인, 회원, 사유) 한 행으로 둔다. 회원은 1 ~ 2850 중 하나다.
 * 회원과 캠페인은 NULL 이 아니다. 조회가 숫자로 읽기 때문이다.
 */
INSERT INTO daily_invalid_analytics (
    daily_invalid_analytics_id, business_date, reason_code, campaign_id, member_id, invalid_count,
    concentration_suspected, created_at, updated_at)
SELECT @b + (c * 100 + 60 - k) * 2 + IF(inv_expired > 0, 0, 1),
       DATE_SUB(@d, INTERVAL k DAY),
       IF(inv_expired > 0, 'EXPIRED_OR_UNKNOWN', 'NOT_OWNER'),
       @b + c,
       @b + 1 + CRC32(CONCAT('inv-', c, '-', k)) % 2850,
       inv_expired + inv_not_owner,
       0,
       TIMESTAMP(DATE_SUB(@d, INTERVAL k - 1 DAY), '01:03:00'),
       TIMESTAMP(DATE_SUB(@d, INTERVAL k - 1 DAY), '01:03:00')
FROM base_rpt
WHERE src = 'F' AND inv_expired + inv_not_owner > 0
ON DUPLICATE KEY UPDATE daily_invalid_analytics_id = daily_invalid_analytics_id;

-- ANA.FRAUD_INVALID_D30 (원천, D-7 ~ D-1): 무효 노출을 (날짜, 캠페인, 회원, 사유)로 센다
INSERT INTO daily_invalid_analytics (
    daily_invalid_analytics_id, business_date, reason_code, campaign_id, member_id, invalid_count,
    concentration_suspected, created_at, updated_at)
SELECT @b + 5000000 + ROW_NUMBER() OVER (ORDER BY business_date, campaign_id, member_id, reason_name),
       business_date, reason_name, campaign_id, member_id, invalid_count, 0,
       TIMESTAMP(DATE_ADD(business_date, INTERVAL 1 DAY), '01:03:00'),
       TIMESTAMP(DATE_ADD(business_date, INTERVAL 1 DAY), '01:03:00')
FROM (
    SELECT business_date, CAST(invalid_reason AS CHAR) AS reason_name, campaign_id, member_id,
           COUNT(*) AS invalid_count
    FROM impression_log
    WHERE business_date BETWEEN DATE_SUB(@d, INTERVAL 7 DAY) AND DATE_SUB(@d, INTERVAL 1 DAY)
      AND validity = 'INVALID' AND invalid_reason IS NOT NULL
      AND campaign_id BETWEEN @b + 1 AND @b + 1300 AND member_id BETWEEN @b + 1 AND @b + 3000
    GROUP BY business_date, invalid_reason, campaign_id, member_id
) counted
ON DUPLICATE KEY UPDATE daily_invalid_analytics_id = daily_invalid_analytics_id;

-- event_log (V6 원천, D-7 ~ D-1)

/*
 * ANA.SOURCE_D1: 노출, 찜, 패스, 발급 시작, 발급, 사용, 만료 이벤트를 원천 행에서 만든다.
 * event_log_id 는 B + 종류 번호 x 1,000,000 + 순번이다. 종류 번호는 노출 1, 찜 2, 패스 3, 발급 시작 4, 발급 5, 사용 6, 만료 7 이다.
 * event_id 는 world- 로 시작해 원천 키로 만든다.
 */
INSERT INTO event_log (
    event_log_id, event_id, event_type, member_id, campaign_id, store_id, serve_id, wish_id, coupon_event_id,
    member_coupon_id, issue_id, occurred_at, created_at, updated_at)
SELECT @b + 1000000 + ROW_NUMBER() OVER (ORDER BY il.business_date, il.serve_id, il.member_id),
       CONCAT('world-imp-', LOWER(HEX(il.serve_id)), '-', il.member_id - @b),
       'impression', il.member_id, il.campaign_id, il.store_id, il.serve_id, NULL, NULL, NULL, NULL,
       il.displayed_at, il.displayed_at, il.displayed_at
FROM impression_log il
WHERE il.business_date BETWEEN DATE_SUB(@d, INTERVAL 7 DAY) AND DATE_SUB(@d, INTERVAL 1 DAY)
  AND il.validity = 'VALID' AND il.store_id IS NOT NULL
  AND il.campaign_id BETWEEN @b + 1 AND @b + 1300 AND il.member_id BETWEEN @b + 1 AND @b + 3000
ON DUPLICATE KEY UPDATE event_log_id = event_log_id;

INSERT INTO event_log (
    event_log_id, event_id, event_type, member_id, campaign_id, store_id, serve_id, wish_id, coupon_event_id,
    member_coupon_id, issue_id, occurred_at, created_at, updated_at)
SELECT @b + 2000000 + ROW_NUMBER() OVER (ORDER BY w.wishlist_id),
       CONCAT('world-wish-', w.wishlist_id),
       'wish', w.member_id, w.campaign_id, w.store_id, w.serve_id, w.wishlist_id, NULL, NULL, NULL,
       w.created_at, w.created_at, w.created_at
FROM wishlist w
WHERE w.created_at >= DATE_SUB(@d, INTERVAL 7 DAY) AND w.created_at < @d
  AND w.campaign_id BETWEEN @b + 1 AND @b + 1300 AND w.member_id BETWEEN @b + 1 AND @b + 3000
ON DUPLICATE KEY UPDATE event_log_id = event_log_id;

INSERT INTO event_log (
    event_log_id, event_id, event_type, member_id, campaign_id, store_id, serve_id, wish_id, coupon_event_id,
    member_coupon_id, issue_id, occurred_at, created_at, updated_at)
SELECT @b + 3000000 + ROW_NUMBER() OVER (ORDER BY s.business_date, s.serve_id, s.member_id),
       CONCAT('world-pass-', LOWER(HEX(s.serve_id)), '-', s.member_id - @b),
       'pass', s.member_id, s.campaign_id, sl.store_id, s.serve_id, NULL, NULL, NULL, NULL,
       s.occurred_at, s.occurred_at, s.occurred_at
FROM swipe_log s
JOIN serve_log sl ON sl.serve_id = s.serve_id
WHERE s.business_date BETWEEN DATE_SUB(@d, INTERVAL 7 DAY) AND DATE_SUB(@d, INTERVAL 1 DAY)
  AND s.action = 'PASS'
  AND s.campaign_id BETWEEN @b + 1 AND @b + 1300 AND s.member_id BETWEEN @b + 1 AND @b + 3000
ON DUPLICATE KEY UPDATE event_log_id = event_log_id;

-- 발급 시작은 회원 없이 회차(coupon_event_id)만 가지고 시각은 11:00 이다
INSERT INTO event_log (
    event_log_id, event_id, event_type, member_id, campaign_id, store_id, serve_id, wish_id, coupon_event_id,
    member_coupon_id, issue_id, occurred_at, created_at, updated_at)
SELECT @b + 4000000 + ROW_NUMBER() OVER (ORDER BY ce.coupon_event_id),
       CONCAT('world-open-', ce.coupon_event_id),
       'issue_open', NULL, ce.campaign_id, ce.store_id, NULL, NULL, ce.coupon_event_id, NULL, NULL,
       TIMESTAMP(ce.business_date, '11:00:00'), TIMESTAMP(ce.business_date, '11:00:00'),
       TIMESTAMP(ce.business_date, '11:00:00')
FROM coupon_event ce
WHERE ce.business_date BETWEEN DATE_SUB(@d, INTERVAL 7 DAY) AND DATE_SUB(@d, INTERVAL 1 DAY)
  AND ce.campaign_id BETWEEN @b + 1 AND @b + 1300
ON DUPLICATE KEY UPDATE event_log_id = event_log_id;

INSERT INTO event_log (
    event_log_id, event_id, event_type, member_id, campaign_id, store_id, serve_id, wish_id, coupon_event_id,
    member_coupon_id, issue_id, occurred_at, created_at, updated_at)
SELECT @b + 5000000 + ROW_NUMBER() OVER (ORDER BY mc.member_coupon_id),
       CONCAT('world-issue-', mc.member_coupon_id),
       'issue', mc.member_id, ce.campaign_id, ce.store_id, NULL, NULL, ce.coupon_event_id, mc.member_coupon_id, NULL,
       mc.created_at, mc.created_at, mc.created_at
FROM member_coupon mc
JOIN coupon_event ce ON ce.coupon_event_id = mc.coupon_event_id
WHERE ce.business_date BETWEEN DATE_SUB(@d, INTERVAL 7 DAY) AND DATE_SUB(@d, INTERVAL 1 DAY)
  AND ce.campaign_id BETWEEN @b + 1 AND @b + 1300 AND mc.member_id BETWEEN @b + 1 AND @b + 3000
ON DUPLICATE KEY UPDATE event_log_id = event_log_id;

INSERT INTO event_log (
    event_log_id, event_id, event_type, member_id, campaign_id, store_id, serve_id, wish_id, coupon_event_id,
    member_coupon_id, issue_id, occurred_at, created_at, updated_at)
SELECT @b + 6000000 + ROW_NUMBER() OVER (ORDER BY cuh.coupon_usage_history_id),
       CONCAT('world-redeem-', cuh.coupon_usage_history_id),
       'redeem', mc.member_id, ce.campaign_id, ce.store_id, NULL, NULL, ce.coupon_event_id, mc.member_coupon_id, NULL,
       cuh.created_at, cuh.created_at, cuh.created_at
FROM coupon_usage_history cuh
JOIN member_coupon mc ON mc.member_coupon_id = cuh.member_coupon_id
JOIN coupon_event ce ON ce.coupon_event_id = mc.coupon_event_id
WHERE cuh.created_at >= DATE_SUB(@d, INTERVAL 7 DAY) AND cuh.created_at < @d
  AND ce.campaign_id BETWEEN @b + 1 AND @b + 1300 AND mc.member_id BETWEEN @b + 1 AND @b + 3000
ON DUPLICATE KEY UPDATE event_log_id = event_log_id;

INSERT INTO event_log (
    event_log_id, event_id, event_type, member_id, campaign_id, store_id, serve_id, wish_id, coupon_event_id,
    member_coupon_id, issue_id, occurred_at, created_at, updated_at)
SELECT @b + 7000000 + ROW_NUMBER() OVER (ORDER BY mc.member_coupon_id),
       CONCAT('world-expire-', mc.member_coupon_id),
       'expire', mc.member_id, ce.campaign_id, ce.store_id, NULL, NULL, ce.coupon_event_id, mc.member_coupon_id, NULL,
       mc.usable_end_at, mc.usable_end_at, mc.usable_end_at
FROM member_coupon mc
JOIN coupon_event ce ON ce.coupon_event_id = mc.coupon_event_id
WHERE mc.status = 'EXPIRED'
  AND ce.business_date BETWEEN DATE_SUB(@d, INTERVAL 7 DAY) AND DATE_SUB(@d, INTERVAL 1 DAY)
  AND ce.campaign_id BETWEEN @b + 1 AND @b + 1300 AND mc.member_id BETWEEN @b + 1 AND @b + 3000
ON DUPLICATE KEY UPDATE event_log_id = event_log_id;

-- 플랫폼 일별 지표 (D-60 ~ D-1, 날짜마다 한 행)

/*
 * ANA.DAILY_METRICS_D60_D1, ANA.CAMPAIGN_REPORT_30D
 * 퍼널은 그날 캠페인 리포트의 합이다. D-7 ~ D-1 은 원천 리포트라 V6 가 없으면 0 이다.
 */
INSERT INTO daily_funnel_analytics (
    business_date, valid_impression_count, invalid_impression_count, wish_count, issue_count, redeem_count,
    created_at, updated_at)
SELECT DATE_SUB(@d, INTERVAL dd.k DAY),
       COALESCE(summed.valid, 0), COALESCE(summed.invalid, 0), COALESCE(summed.wish, 0),
       COALESCE(summed.issued, 0), COALESCE(summed.redeem, 0),
       TIMESTAMP(DATE_SUB(@d, INTERVAL dd.k - 1 DAY), '01:03:00'),
       TIMESTAMP(DATE_SUB(@d, INTERVAL dd.k - 1 DAY), '01:03:00')
FROM base_day dd
LEFT JOIN (
    SELECT k, SUM(imp_valid) AS valid, SUM(inv_expired + inv_not_owner) AS invalid, SUM(wish) AS wish,
           SUM(issued) AS issued, SUM(redeem) AS redeem
    FROM base_rpt
    GROUP BY k
) summed ON summed.k = dd.k
WHERE dd.k BETWEEN 1 AND 60
ON DUPLICATE KEY UPDATE daily_funnel_analytics.business_date = daily_funnel_analytics.business_date;

/*
 * ANA.DAILY_METRICS_D60_D1
 * D-60 ~ D-8 은 공식이다. 차감은 그날 리포트 과금 합, 예약은 하루 예산 합, 해제는 그 차이다. 충전, 환불, 조정과 미소진 잔액은 공식이다.
 * 10일마다 조정 -2000 과 +1000 이 있어 adjust_amount -1000, positive_adjust_amount 1000 이다.
 * D-7 ~ D-1 은 point_ledger 를 집계 작업(SettlementMetricsStep)과 같은 기준으로 모은다. 미소진 잔액은 다음 날 00:00 전까지의 CHARGE, REFUND, ADJUST, DEDUCT 누적 합이다.
 */
INSERT INTO daily_settlement_analytics (
    business_date, charge_amount, deduct_amount, reserved_amount, released_amount, refund_amount, adjust_amount,
    positive_adjust_amount, unspent_balance, created_at, updated_at)
SELECT DATE_SUB(@d, INTERVAL dd.k DAY),
       IF(dd.k >= 8, 50000 * (2 + CRC32(CONCAT('chg-', dd.k)) % 6), COALESCE(charged.amount, 0)),
       IF(dd.k >= 8, COALESCE(summed.charged, 0), COALESCE(deducted.amount, 0)),
       IF(dd.k >= 8, COALESCE(summed.budget, 0), COALESCE(reserved.reserve_amount, 0)),
       IF(dd.k >= 8, COALESCE(summed.budget, 0) - COALESCE(summed.charged, 0), COALESCE(reserved.release_amount, 0)),
       IF(dd.k >= 8, IF(dd.k % 5 = 0, 30000, 0), COALESCE(charged.refund, 0)),
       IF(dd.k >= 8, IF(dd.k % 10 = 0, -1000, 0), COALESCE(adjusted.amount, 0)),
       IF(dd.k >= 8, IF(dd.k % 10 = 0, 1000, 0), COALESCE(adjusted.positive, 0)),
       IF(dd.k >= 8, 50000000 - (60 - dd.k) * 100000 + CRC32(CONCAT('uns-', dd.k)) % 100000,
          GREATEST(0, (SELECT COALESCE(SUM(pl.amount), 0) FROM point_ledger pl
                       WHERE pl.entry_type IN ('CHARGE', 'REFUND', 'ADJUST', 'DEDUCT')
                         AND pl.owner_id BETWEEN @b + 1 AND @b + 300
                         AND pl.created_at < DATE_SUB(@d, INTERVAL dd.k - 1 DAY)))),
       TIMESTAMP(DATE_SUB(@d, INTERVAL dd.k - 1 DAY), '01:03:00'),
       TIMESTAMP(DATE_SUB(@d, INTERVAL dd.k - 1 DAY), '01:03:00')
FROM base_day dd
LEFT JOIN (
    SELECT k, SUM(charged) AS charged, SUM(daily_budget) AS budget
    FROM base_rpt
    WHERE src = 'F'
    GROUP BY k
) summed ON summed.k = dd.k
LEFT JOIN (
    SELECT DATE(created_at) AS business_date,
           SUM(IF(entry_type = 'CHARGE', amount, 0)) AS amount,
           -SUM(IF(entry_type = 'REFUND', amount, 0)) AS refund
    FROM point_ledger
    WHERE entry_type IN ('CHARGE', 'REFUND') AND owner_id BETWEEN @b + 1 AND @b + 300
      AND created_at >= DATE_SUB(@d, INTERVAL 7 DAY) AND created_at < @d
    GROUP BY DATE(created_at)
) charged ON charged.business_date = DATE_SUB(@d, INTERVAL dd.k DAY)
LEFT JOIN (
    SELECT dr.business_date, -SUM(pl.amount) AS amount
    FROM point_ledger pl
    JOIN deduction_result dr ON dr.serve_id = pl.serve_id
    WHERE pl.entry_type = 'DEDUCT' AND pl.owner_id BETWEEN @b + 1 AND @b + 300
      AND dr.business_date BETWEEN DATE_SUB(@d, INTERVAL 7 DAY) AND DATE_SUB(@d, INTERVAL 1 DAY)
    GROUP BY dr.business_date
) deducted ON deducted.business_date = DATE_SUB(@d, INTERVAL dd.k DAY)
LEFT JOIN (
    SELECT business_date,
           -SUM(IF(entry_type = 'RESERVE', amount, 0)) AS reserve_amount,
           SUM(IF(entry_type = 'RELEASE', amount, 0)) AS release_amount
    FROM point_ledger
    WHERE entry_type IN ('RESERVE', 'RELEASE') AND owner_id BETWEEN @b + 1 AND @b + 300
      AND business_date BETWEEN DATE_SUB(@d, INTERVAL 7 DAY) AND DATE_SUB(@d, INTERVAL 1 DAY)
    GROUP BY business_date
) reserved ON reserved.business_date = DATE_SUB(@d, INTERVAL dd.k DAY)
LEFT JOIN (
    SELECT COALESCE(adjust_target_date, DATE(created_at)) AS business_date,
           SUM(amount) AS amount,
           SUM(IF(amount > 0, amount, 0)) AS positive
    FROM point_ledger
    WHERE entry_type = 'ADJUST' AND owner_id BETWEEN @b + 1 AND @b + 300
    GROUP BY COALESCE(adjust_target_date, DATE(created_at))
) adjusted ON adjusted.business_date = DATE_SUB(@d, INTERVAL dd.k DAY)
WHERE dd.k BETWEEN 1 AND 60
ON DUPLICATE KEY UPDATE daily_settlement_analytics.business_date = daily_settlement_analytics.business_date;

/*
 * ANA.DAILY_METRICS_D60_D1
 * 가게 지표는 WORLD_SPEC 공식으로 센다 (원천이 V6 가 아니다). 확정 가게(점주 1 ~ 250, 291 ~ 300)는 점주 가입일 D-(120 - n % 110) 에 생긴 것으로 본다.
 * 진행 중 캠페인은 ACTIVE 1 ~ 180 (179 빼고, D-14 부터), 242 (D-10 부터), ENDED 1000 + n 의 7일 기간이다.
 */
INSERT INTO daily_store_analytics (
    business_date, new_store_count, cumulative_store_count, active_campaign_count, created_at, updated_at)
SELECT DATE_SUB(@d, INTERVAL dd.k DAY),
       SUM((n.n <= 250 OR n.n BETWEEN 291 AND 300) AND 120 - n.n % 110 = dd.k),
       SUM((n.n <= 250 OR n.n BETWEEN 291 AND 300) AND 120 - n.n % 110 >= dd.k),
       IF(dd.k <= 14, 179, 0) + IF(dd.k <= 10, 1, 0)
           + SUM(n.n <= 300 AND dd.k BETWEEN 14 + n.n % 60 AND 20 + n.n % 60),
       TIMESTAMP(DATE_SUB(@d, INTERVAL dd.k - 1 DAY), '01:03:00'),
       TIMESTAMP(DATE_SUB(@d, INTERVAL dd.k - 1 DAY), '01:03:00')
FROM base_day dd
CROSS JOIN base_n n
WHERE dd.k BETWEEN 1 AND 60 AND n.n <= 300
GROUP BY dd.k
ON DUPLICATE KEY UPDATE daily_store_analytics.business_date = daily_store_analytics.business_date;

/*
 * ANA.DAILY_METRICS_D60_D1
 * 신규, 누적, 탈퇴는 WORLD_SPEC 회원 공식(가입일 D-(1 + n % 60), 2981 ~ 3000 은 D-2 탈퇴)으로 센다.
 * 활성 회원은 D-60 ~ D-8 은 공식이고 D-7 ~ D-1 은 그날 event_log 에 기록된 회원 수다. 정지 수는 집계 작업이 쓰지 않아 0 이다.
 */
INSERT INTO daily_member_analytics (
    business_date, new_member_count, cumulative_member_count, active_member_count, withdrawn_count,
    suspended_count, created_at, updated_at)
SELECT DATE_SUB(@d, INTERVAL dd.k DAY),
       SUM(1 + n.n % 60 = dd.k),
       SUM(1 + n.n % 60 >= dd.k AND NOT (n.n >= 2981 AND dd.k <= 2)),
       IF(dd.k >= 8, 450 + CRC32(CONCAT('act-', dd.k)) % 150,
          (SELECT COUNT(DISTINCT el.member_id) FROM event_log el
           WHERE el.member_id BETWEEN @b + 1 AND @b + 3000
             AND el.occurred_at >= DATE_SUB(@d, INTERVAL dd.k DAY)
             AND el.occurred_at < DATE_SUB(@d, INTERVAL dd.k - 1 DAY))),
       SUM(n.n >= 2981 AND dd.k = 2),
       0,
       TIMESTAMP(DATE_SUB(@d, INTERVAL dd.k - 1 DAY), '01:03:00'),
       TIMESTAMP(DATE_SUB(@d, INTERVAL dd.k - 1 DAY), '01:03:00')
FROM base_day dd
CROSS JOIN base_n n
WHERE dd.k BETWEEN 1 AND 60
GROUP BY dd.k
ON DUPLICATE KEY UPDATE daily_member_analytics.business_date = daily_member_analytics.business_date;

-- 점주와 회원과 분해 지표

/*
 * ANA.CAMPAIGN_REPORT_30D
 * 점주 소진은 그날 리포트 소진 합이다. 캠페인 없는 점주 243 의 D-12 는 캠페인 없는 양수 조정 500 으로 -500 인 날이다.
 */
INSERT INTO daily_owner_spend (business_date, owner_id, spent_amount, created_at, updated_at)
SELECT DATE_SUB(@d, INTERVAL k DAY),
       @b + store_n,
       SUM(spent),
       TIMESTAMP(DATE_SUB(@d, INTERVAL k - 1 DAY), '01:03:00'),
       TIMESTAMP(DATE_SUB(@d, INTERVAL k - 1 DAY), '01:03:00')
FROM (
    SELECT k, IF(c > 1000, (c - 1001) % 250 + 1, c) AS store_n, charged - refunded AS spent
    FROM base_rpt
    WHERE k >= 1
) owned
GROUP BY k, store_n
ON DUPLICATE KEY UPDATE daily_owner_spend.business_date = daily_owner_spend.business_date;

INSERT INTO daily_owner_spend (business_date, owner_id, spent_amount, created_at, updated_at)
VALUES (DATE_SUB(@d, INTERVAL 12 DAY), @b + 243, -500,
        TIMESTAMP(DATE_SUB(@d, INTERVAL 11 DAY), '01:03:00'), TIMESTAMP(DATE_SUB(@d, INTERVAL 11 DAY), '01:03:00'))
ON DUPLICATE KEY UPDATE daily_owner_spend.business_date = daily_owner_spend.business_date;

-- ANA.DAILY_MEMBER_USAGE (공식, D-60 ~ D-8): 회원 1 ~ 2850 중 (n * 7 + k) % 25 = 0 인 회원이 그날 찜 1 ~ 3, 사용 0 ~ 1 을 남긴다
INSERT INTO daily_member_usage (business_date, member_id, wish_count, redeem_count, saved_amount, created_at, updated_at)
SELECT DATE_SUB(@d, INTERVAL dd.k DAY),
       @b + n.n,
       1 + (n.n + dd.k) % 3,
       (n.n + dd.k) % 2,
       ((n.n + dd.k) % 2) * (1000 + (n.n % 5) * 1000),
       TIMESTAMP(DATE_SUB(@d, INTERVAL dd.k - 1 DAY), '01:03:00'),
       TIMESTAMP(DATE_SUB(@d, INTERVAL dd.k - 1 DAY), '01:03:00')
FROM base_day dd
CROSS JOIN base_n n
WHERE dd.k BETWEEN 8 AND 60 AND n.n <= 2850 AND (n.n * 7 + dd.k) % 25 = 0
ON DUPLICATE KEY UPDATE daily_member_usage.business_date = daily_member_usage.business_date;

-- ANA.DAILY_MEMBER_USAGE (원천, D-7 ~ D-1): 찜은 wishlist 생성일, 사용과 할인액은 coupon_usage_history 기록일로 센다
INSERT INTO daily_member_usage (business_date, member_id, wish_count, redeem_count, saved_amount, created_at, updated_at)
SELECT business_date, member_id, SUM(wish_count), SUM(redeem_count), SUM(saved_amount),
       TIMESTAMP(DATE_ADD(business_date, INTERVAL 1 DAY), '01:03:00'),
       TIMESTAMP(DATE_ADD(business_date, INTERVAL 1 DAY), '01:03:00')
FROM (
    SELECT DATE(created_at) AS business_date, member_id, COUNT(*) AS wish_count, 0 AS redeem_count, 0 AS saved_amount
    FROM wishlist
    WHERE created_at >= DATE_SUB(@d, INTERVAL 7 DAY) AND created_at < @d
      AND member_id BETWEEN @b + 1 AND @b + 3000 AND campaign_id BETWEEN @b + 1 AND @b + 1300
    GROUP BY DATE(created_at), member_id
    UNION ALL
    SELECT DATE(cuh.created_at), mc.member_id, 0, COUNT(*), SUM(cuh.discount_amount)
    FROM coupon_usage_history cuh
    JOIN member_coupon mc ON mc.member_coupon_id = cuh.member_coupon_id
    WHERE cuh.created_at >= DATE_SUB(@d, INTERVAL 7 DAY) AND cuh.created_at < @d
      AND mc.member_id BETWEEN @b + 1 AND @b + 3000
    GROUP BY DATE(cuh.created_at), mc.member_id
) used
GROUP BY business_date, member_id
ON DUPLICATE KEY UPDATE daily_member_usage.business_date = daily_member_usage.business_date;

-- 업종과 지역별 분해는 그날 리포트를 가게 업종 cats[n % 9] 와 가게 지역(n 211 ~ 260 홍대, 291 ~ 300 잠실, 나머지 강남)으로 묶는다
INSERT INTO daily_segment_analytics (
    business_date, category_code, region, impression_count, wish_count, issued_count, redeem_count, spent_amount,
    created_at, updated_at)
SELECT DATE_SUB(@d, INTERVAL k DAY), category_code, region,
       SUM(imp_valid), SUM(wish), SUM(issued), SUM(redeem), SUM(charged - refunded),
       TIMESTAMP(DATE_SUB(@d, INTERVAL k - 1 DAY), '01:03:00'),
       TIMESTAMP(DATE_SUB(@d, INTERVAL k - 1 DAY), '01:03:00')
FROM (
    SELECT k, imp_valid, wish, issued, redeem, charged, refunded,
           ELT(IF(c > 1000, (c - 1001) % 250 + 1, c) % 9 + 1, 'KOREAN', 'CHINESE', 'JAPANESE', 'WESTERN', 'BUNSIK',
               'ASIAN', 'FAST_FOOD', 'CAFE_DESSERT', 'OTHER') AS category_code,
           CASE
               WHEN IF(c > 1000, (c - 1001) % 250 + 1, c) BETWEEN 211 AND 260 THEN '서울 마포구'
               WHEN IF(c > 1000, (c - 1001) % 250 + 1, c) BETWEEN 291 AND 300 THEN '서울 송파구'
               ELSE '서울 강남구'
           END AS region
    FROM base_rpt
    WHERE k >= 1
) located
GROUP BY k, category_code, region
ON DUPLICATE KEY UPDATE daily_segment_analytics.business_date = daily_segment_analytics.business_date;

-- batch_execution_log

/*
 * OPS.AGG_FINAL_D60_D1, OPS.AGG_SUCCESS_D2, OPS.BATCH_HISTORY_31D, OPS.BATCH_SUCCESS_D, OPS.BILLING_RELEASE_SUCCESS_D1
 * 작업 이름은 코드의 값이다 (BatchJobRegistry.BUNDLE, 각 DailyJob 과 StandaloneJob 의 jobName).
 * day_shift 는 실행 시각이 대상일 다음 날인지(1), 같은 날인지(0)다. min_k 는 가장 최근 대상일이 D 에서 며칠 전인지다.
 * D 행은 기준 시각(11:00 전)보다 먼저 도는 작업만 둔다. COUPON_OPEN, COUPON_CLOSE, COUPON_EXPIRE 의 D 는 아직 돌지 않아 넣지 않는다.
 * 모두 SUCCESS 이고 RUNNING 과 FAILED 는 넣지 않는다.
 */
CREATE TEMPORARY TABLE base_job (
    j INT PRIMARY KEY,
    job_name VARCHAR(50) NOT NULL,
    min_k INT NOT NULL,
    day_shift INT NOT NULL,
    start_time TIME NOT NULL,
    duration_sec INT NOT NULL
);

INSERT INTO base_job (j, job_name, min_k, day_shift, start_time, duration_sec) VALUES
    (1, 'DAILY_0000', 0, 0, '00:00:00', 50),
    (2, 'BILLING_SPEND_RECON_PREV', 0, 0, '00:00:01', 4),
    (3, 'BILLING_RESERVE', 0, 0, '00:00:05', 10),
    (4, 'BILLING_FUNDING_JUDGE', 0, 0, '00:00:16', 4),
    (5, 'BILLING_HOURLY_TARGET', 0, 0, '00:00:21', 4),
    (6, 'ADSERVING_CANDIDATE_LOAD', 0, 0, '00:00:26', 9),
    (7, 'ADSERVING_ENDED_WISHLIST_CLEANUP', 0, 0, '00:00:36', 4),
    (8, 'ADSERVING_STORE_AUDIENCE', 0, 0, '00:00:41', 8),
    (9, 'BILLING_DAILY_RELEASE', 1, 1, '00:00:14', 1),
    (10, 'DAILY_AGGREGATION', 1, 1, '01:00:00', 300),
    (11, 'BILLING_PG_VS_CHARGE_RECON', 0, 0, '01:30:00', 60),
    (12, 'ADSERVING_LOG_RETENTION', 0, 0, '02:00:00', 40),
    (13, 'COUPON_VERIFY', 1, 1, '02:00:00', 90),
    (14, 'MEMBER_KAKAO_UNLINK_RETRY', 0, 0, '03:00:00', 20),
    (15, 'COUPON_PREPARE', 0, 0, '03:00:00', 60),
    (16, 'EVENT_LOG_RETENTION', 0, 0, '04:00:00', 30),
    (17, 'CAMPAIGN_TEMPLATE_VERSION_PURGE', 0, 0, '04:00:00', 10),
    (18, 'COUPON_OPEN', 1, 0, '11:00:00', 30),
    (19, 'COUPON_CLOSE', 1, 0, '14:00:00', 30),
    (20, 'COUPON_EXPIRE', 1, 0, '15:00:00', 30);

/*
 * batch_execution_log_id = B + j * 100 + (60 - k) 이다. 다른 파일이 job_execution_id 로 가리킬 때 이 공식을 쓴다.
 * 처리 건수는 DAILY_AGGREGATION 1, DAILY_0000 은 단계 수 7, 나머지는 공식이다.
 */
INSERT INTO batch_execution_log (
    batch_execution_log_id, job_name, business_date, status, finished_at, failure_reason, processed_count, owner_id,
    retry_count, retry_reason, attempt_no, created_at, updated_at)
SELECT @b + bj.j * 100 + (60 - dd.k),
       bj.job_name,
       DATE_SUB(@d, INTERVAL dd.k DAY),
       'SUCCESS',
       TIMESTAMP(DATE_SUB(@d, INTERVAL dd.k - bj.day_shift DAY), bj.start_time) + INTERVAL bj.duration_sec SECOND,
       NULL,
       CASE bj.job_name
           WHEN 'DAILY_AGGREGATION' THEN 1
           WHEN 'DAILY_0000' THEN 7
           ELSE CRC32(CONCAT(bj.job_name, '-', dd.k)) % 50 + 1
       END,
       'seed-batch',
       0,
       NULL,
       1,
       TIMESTAMP(DATE_SUB(@d, INTERVAL dd.k - bj.day_shift DAY), bj.start_time),
       TIMESTAMP(DATE_SUB(@d, INTERVAL dd.k - bj.day_shift DAY), bj.start_time) + INTERVAL bj.duration_sec SECOND
FROM base_job bj
CROSS JOIN base_day dd
WHERE dd.k BETWEEN bj.min_k AND 60
ON DUPLICATE KEY UPDATE batch_execution_log_id = batch_execution_log_id;

DROP TEMPORARY TABLE base_job;
DROP TEMPORARY TABLE base_rpt;
DROP TEMPORARY TABLE base_cday;
DROP TEMPORARY TABLE base_day;
DROP TEMPORARY TABLE base_n;
