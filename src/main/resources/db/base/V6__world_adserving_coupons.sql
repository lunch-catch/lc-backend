/*
 * 기본 세계 V6: 광고 서빙과 쿠폰 (WORLD_SPEC "광고 서빙과 쿠폰 (V6)").
 * 과거 7일(D-7 ~ D-1, 일차 dn = 1 ~ 7)과 오늘 D(dn = 8)의 서빙, 노출, 스와이프, 찜, 차감, 예약과 해제 원장, 쿠폰 회차와 재고와 발급을 만든다.
 * 캠페인, 가게, 회원 값은 다른 V 파일을 읽지 않고 WORLD_SPEC 공식으로 다시 계산한다.
 * 오늘 D 는 11:00 전 상태다. 오늘 노출은 08:00 ~ 10:50 에만 있고 오늘 쿠폰은 하나도 발급하지 않는다.
 * 번호는 B = 10000000 을 더한 고정 번호다. point_ledger 는 B+1000000 부터 쓴다.
 */

/*
 * 숫자 표 base_n 1 ~ 10000 (자리 숫자 교차 조인).
 * 임시 표는 한 쿼리에서 두 번 열 수 없어 안쪽 반복용 base_k(1 ~ 100)를 따로 둔다.
 */
CREATE TEMPORARY TABLE base_n (n INT PRIMARY KEY);
INSERT INTO base_n (n)
WITH digit AS (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
               UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)
SELECT a.d + b.d * 10 + c.d * 100 + e.d * 1000 + 1
  FROM digit a CROSS JOIN digit b CROSS JOIN digit c CROSS JOIN digit e;
CREATE TEMPORARY TABLE base_k (n INT PRIMARY KEY);
INSERT INTO base_k (n) SELECT n FROM base_n WHERE n <= 100;

/*
 * 일차 dn 과 날짜.
 * dn = 8 - (D 에서 거슬러 간 날 수), D 는 8, D-8 은 0.
 */
CREATE TEMPORARY TABLE base_day (dn INT PRIMARY KEY, bdate DATE NOT NULL, d0 DATETIME(6) NOT NULL);
INSERT INTO base_day (dn, bdate, d0)
SELECT n - 1, DATE_SUB('${baseDate}', INTERVAL 9 - n DAY), TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 9 - n DAY))
  FROM base_n WHERE n <= 9;

-- 업종 cats[k] 와 한글 이름 (V3 가게 이름과 같은 표)
CREATE TEMPORARY TABLE base_cat (k INT PRIMARY KEY, code VARCHAR(30) NOT NULL, label VARCHAR(20) NOT NULL);
INSERT INTO base_cat VALUES
    (0, 'KOREAN', '한식'), (1, 'CHINESE', '중식'), (2, 'JAPANESE', '일식'), (3, 'WESTERN', '양식'),
    (4, 'BUNSIK', '분식'), (5, 'ASIAN', '아시안'), (6, 'FAST_FOOD', '패스트푸드'), (7, 'CAFE_DESSERT', '카페디저트'),
    (8, 'OTHER', '기타');

/*
 * 캠페인 1 ~ 205 와 242 의 값을 WORLD_SPEC 공식으로 계산한다.
 * 상태: 1 ~ 180, 242, 301 ~ 330 (#858) ACTIVE, 181 ~ 195 PAUSED OWNER, 196 ~ 205 PAUSED NO_POINTS.
 * 가게 좌표는 V3 가 넣은 store 행에서 읽는다 (역 둘레 공식, #858).
 * 기간은 D-14 ~ D+28, 179 는 D 시작, 341 은 D 종료(역 밖), 242 는 D-1 종료다.
 */
CREATE TEMPORARY TABLE base_cmp (
    n INT PRIMARY KEY,
    campaign_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    cstate VARCHAR(20) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    cat_code VARCHAR(30) NOT NULL,
    store_name VARCHAR(100) NOT NULL,
    store_image_url VARCHAR(512) NOT NULL,
    latitude DECIMAL(10, 7) NOT NULL,
    longitude DECIMAL(10, 7) NOT NULL,
    target_radius INT NOT NULL,
    target_gender VARCHAR(10) NOT NULL,
    target_age_groups VARCHAR(50) NOT NULL,
    dtarget VARCHAR(10) NOT NULL,
    dtype VARCHAR(10) NOT NULL,
    dvalue INT NOT NULL,
    menu_id BIGINT NULL,
    menu_name VARCHAR(100) NULL,
    menu_price BIGINT NULL,
    menu_disc_price BIGINT NULL,
    discount_amount INT NOT NULL,
    ustart TIME NOT NULL,
    uend TIME NOT NULL,
    qty INT NOT NULL,
    budget BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    status_changed_at DATETIME(6) NOT NULL
);
INSERT INTO base_cmp
SELECT x.n, 10000000 + x.n, 10000000 + x.n, 10000000 + x.n,
       CASE WHEN x.n <= 180 OR x.n = 242 OR x.n BETWEEN 301 AND 330 OR x.n = 341 THEN 'ACTIVE' WHEN x.n <= 195 THEN 'PAUSED_OWNER' ELSE 'PAUSED_NO_POINTS' END,
       x.start_date, x.end_date, c.code,
       CONCAT(c.label, ' ', LPAD(x.n, 3, '0'), '호점'),
       CONCAT(TRIM(TRAILING '/' FROM '${baseCdnBaseUrl}'), '/world/stores/', 10000000 + x.n, '/logo.jpg'),
       st.latitude,
       st.longitude,
       IF(x.n % 3 = 0, 1000, ELT(x.n % 4 + 1, 500, 1000, 2000, 3000)),
       IF(x.n % 3 = 0, 'ALL', ELT(x.n % 3 + 1, 'ALL', 'MALE', 'FEMALE')),
       IF(x.n % 3 = 0, 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS',
          ELT(x.n % 4 + 1, 'AGE_20S', 'AGE_20S,AGE_30S', 'AGE_20S,AGE_30S,AGE_40S', 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS')),
       IF(x.is_menu, 'MENU', 'ALL'),
       IF(x.is_menu AND x.n % 8 <> 6, 'PERCENT', 'AMOUNT'),
       x.dvalue,
       IF(x.is_menu, 10000000 + x.n * 10 + 1, NULL),
       IF(x.is_menu, CONCAT(c.label, ' 메뉴 1'), NULL),
       IF(x.is_menu, x.price, NULL),
       IF(x.is_menu, x.price - x.menu_cut, NULL),
       IF(x.is_menu, x.menu_cut, x.dvalue),
       ELT(x.n % 4 + 1, '11:30:00', '12:00:00', '11:30:00', '13:00:00'),
       ELT(x.n % 4 + 1, '13:30:00', '14:00:00', '15:00:00', '15:00:00'),
       IF(x.n % 17 = 0, 3, ELT(x.n % 5 + 1, 10, 20, 30, 50, 100)),
       ELT(x.n % 4 + 1, 5000, 10000, 20000, 30000),
       TIMESTAMP(DATE_SUB(x.start_date, INTERVAL 1 DAY), '10:00:00'),
       CASE WHEN x.n <= 180 OR x.n = 242 OR x.n BETWEEN 301 AND 330 OR x.n = 341 THEN TIMESTAMP(x.start_date)
            WHEN x.n <= 195 THEN TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 3 DAY), '15:00:00')
            ELSE TIMESTAMP('${baseDate}') END
  FROM (
    SELECT y.*,
           -- 할인 대상 메뉴 1번 가격에서 깎는 금액 (CouponDiscountPrice: 10원 단위 반올림, 가격 넘지 않음)
           IF(y.is_menu, LEAST(y.price, IF(y.n % 8 = 6, 2000, ROUND(y.price * (10 + y.n % 3 * 5) / 100 / 10) * 10)), 0) AS menu_cut,
           IF(y.is_menu, IF(y.n % 8 = 6, 2000, 10 + y.n % 3 * 5), 1000 + (y.n % 5) * 1000) AS dvalue
      FROM (
        SELECT b.n,
               b.n % 4 > 0 AND b.n % 2 = 0 AS is_menu,
               8000 + (b.n * 500 + 1000) % 7000 AS price,
               IF(b.n = 179, DATE('${baseDate}'), DATE_SUB('${baseDate}', INTERVAL 14 DAY)) AS start_date,
               CASE WHEN b.n = 341 THEN DATE('${baseDate}') WHEN b.n = 242 THEN DATE_SUB('${baseDate}', INTERVAL 1 DAY)
                    ELSE DATE_ADD('${baseDate}', INTERVAL 28 DAY) END AS end_date
          FROM base_n b WHERE b.n <= 205 OR b.n = 242 OR b.n BETWEEN 301 AND 330 OR b.n = 341
      ) y
  ) x
  JOIN base_cat c ON c.k = x.n % 9
  JOIN store st ON st.store_id = 10000000 + x.n;

/*
 * 서빙: 날짜마다 회원 m(1 ~ 2850, (m + dn) % 5 = 0)에게 카드 4장. 진행 중 캠페인 210개 중 k = (m * 7 + i * 31 + dn) % 210 번째다.
 * k < 180 이면 캠페인 k + 1, 아니면 k + 121 (301 ~ 330, #858) 이다.
 * 시작 전인 179 는 과거 날짜에서 뺀다. 회원 1 은 사용 내역이 많은 회원이라 아래에서 따로 만든다.
 * s 는 서빙 일련번호 dn * 100000 + m * 10 + i 이고 serve_id, deduction_result_id, DEDUCT 원장 번호의 바탕이다.
 */
CREATE TEMPORARY TABLE base_serve (
    s INT PRIMARY KEY,
    dn INT NOT NULL,
    bdate DATE NOT NULL,
    m INT NOT NULL,
    i INT NOT NULL,
    n INT NOT NULL,
    served_at DATETIME(6) NOT NULL,
    slot INT NOT NULL,
    valid TINYINT NOT NULL,
    wish TINYINT NOT NULL,
    issue_cand TINYINT NOT NULL,
    heavy TINYINT NOT NULL,
    KEY idx_base_serve_nd (n, dn),
    KEY idx_base_serve_md (m, dn)
);
INSERT INTO base_serve
SELECT z.s, z.dn, z.bdate, z.m, z.i, z.n, z.served_at, z.slot, z.valid,
       z.valid AND CRC32(CONCAT('wish', z.s)) % 10 < 3,
       z.valid AND z.dn < 8 AND CRC32(CONCAT('wish', z.s)) % 10 < 3 AND CRC32(CONCAT('issue', z.s)) % 2 = 0,
       0
  FROM (
    SELECT y.*, NOT (y.slot = 10 AND (y.m + y.dn) % 2 = 0 AND y.dn < 8) AS valid
      FROM (
        SELECT d.dn * 100000 + mm.n * 10 + ii.n AS s, d.dn, d.bdate, mm.n AS m, ii.n AS i,
               IF((mm.n * 7 + ii.n * 31 + d.dn) % 210 < 180, (mm.n * 7 + ii.n * 31 + d.dn) % 210 + 1,
                  (mm.n * 7 + ii.n * 31 + d.dn) % 210 + 121) AS n,
               IF(d.dn < 8,
                  d.d0 + INTERVAL 11 HOUR + INTERVAL ((mm.n * 37 + d.dn * 11) % 140) MINUTE + INTERVAL (ii.n * 20) SECOND,
                  d.d0 + INTERVAL 8 HOUR + INTERVAL ((mm.n * 37 + d.dn * 11) % 170) MINUTE + INTERVAL (ii.n * 20) SECOND) AS served_at,
               IF(ii.n = 4 AND (mm.n + d.dn) % 3 = 0, 10, ii.n + (mm.n * 3 + d.dn) % 6) AS slot
          FROM base_day d
          JOIN base_n mm ON mm.n BETWEEN 2 AND 2850 AND (mm.n + d.dn) % 5 = 0
          JOIN base_k ii ON ii.n <= 4
         WHERE d.dn BETWEEN 1 AND 8
      ) y
     WHERE NOT (y.n = 179 AND y.dn < 8)
  ) z;

-- CPN.HISTORY_MANY: 회원 1 은 과거 7일 매일 3장(캠페인 2 ~ 22)을 받아 쓰거나 만료해 사용 내역이 21건이다
INSERT INTO base_serve
SELECT d.dn * 100000 + 10 + j.n, d.dn, d.bdate, 1, j.n, ((d.dn - 1) * 3 + j.n) % 180 + 1,
       d.d0 + INTERVAL 11 HOUR + INTERVAL (j.n * 20 - 10) MINUTE, j.n, 1, 1, 1, 1
  FROM base_day d JOIN base_n j ON j.n <= 3
 WHERE d.dn BETWEEN 1 AND 7;

-- 서빙 기록
INSERT INTO serve_log (serve_id, business_date, feed_id, member_id, campaign_id, store_id, slot_type, slot_position,
                       served_at, created_at, updated_at)
SELECT UNHEX(CONCAT('000000000001', '7000', '8', LPAD(HEX(s), 15, '0'))), bdate,
       UNHEX(CONCAT('000000000002', '7000', '8', LPAD(HEX(dn * 10000 + m), 15, '0'))),
       10000000 + m, 10000000 + n, 10000000 + n, IF(slot % 2 = 1, 'ALLOCATION', 'RELEVANCE'), slot,
       served_at, served_at, served_at
  FROM base_serve
    ON DUPLICATE KEY UPDATE serve_log.serve_id = serve_log.serve_id;

/*
 * 노출: 유효 노출은 서빙 2초 뒤 표시. 슬롯 10 의 일부(과거 날짜, (m + dn) % 2 = 0)는 INVALID EXPIRED_OR_UNKNOWN 이다.
 * 무효 노출은 서빙 기록을 못 찾은 경우라 store_id 와 슬롯을 비우고 표시 시각을 2시간 뒤로 둔다.
 * 위치는 회원 위치 공식(m % 3 의 역 둘레 r = (m * 13) % 1500, 각도 (m * 71) % 360, V2 와 같음)을 소수 3자리로 반올림한다.
 */
INSERT INTO impression_log (serve_id, member_id, business_date, campaign_id, store_id, slot_type, slot_position,
                            displayed_at, lat_rounded, lng_rounded, validity, invalid_reason, sold_out, viewed_ms,
                            created_at, updated_at)
SELECT UNHEX(CONCAT('000000000001', '7000', '8', LPAD(HEX(s), 15, '0'))), 10000000 + m, bdate, 10000000 + n,
       IF(valid, 10000000 + n, NULL), IF(valid, IF(slot % 2 = 1, 'ALLOCATION', 'RELEVANCE'), NULL), IF(valid, slot, NULL),
       IF(valid, served_at + INTERVAL 2 SECOND, served_at + INTERVAL 2 HOUR),
       ROUND(ROUND(ELT(m % 3 + 1, 37.4979, 37.5006, 37.5045) + ((m * 13) % 1500) * COS(RADIANS((m * 71) % 360)) / 111000, 7), 3),
       ROUND(ROUND(ELT(m % 3 + 1, 127.0276, 127.0364, 127.0490) + ((m * 13) % 1500) * SIN(RADIANS((m * 71) % 360)) / 88200, 7), 3),
       IF(valid, 'VALID', 'INVALID'), IF(valid, NULL, 'EXPIRED_OR_UNKNOWN'), 0, IF(valid, 1500 + CRC32(CONCAT('view', s)) % 4000, NULL),
       IF(valid, served_at + INTERVAL 2 SECOND, served_at + INTERVAL 2 HOUR),
       IF(valid, served_at + INTERVAL 2 SECOND, served_at + INTERVAL 2 HOUR)
  FROM base_serve
    ON DUPLICATE KEY UPDATE impression_log.serve_id = impression_log.serve_id;

-- ADS.INVALID_IMPRESSION_TODAY: 오늘 10:00 에 받은 서버 발급이 아닌 serve_id 의 무효 노출 10건 (EXPIRED_OR_UNKNOWN 6, NOT_OWNER 4)
INSERT INTO impression_log (serve_id, member_id, business_date, campaign_id, store_id, slot_type, slot_position,
                            displayed_at, lat_rounded, lng_rounded, validity, invalid_reason, sold_out, viewed_ms,
                            created_at, updated_at)
SELECT UNHEX(CONCAT('000000000005', '7000', '8', LPAD(HEX(k.n), 15, '0'))), 10000000 + 100 + k.n, d.bdate, 10000000 + 3,
       NULL, NULL, NULL, d.d0 + INTERVAL 10 HOUR + INTERVAL k.n MINUTE, 37.500, 127.030, 'INVALID',
       IF(k.n <= 6, 'EXPIRED_OR_UNKNOWN', 'NOT_OWNER'), 0, NULL,
       d.d0 + INTERVAL 10 HOUR + INTERVAL k.n MINUTE, d.d0 + INTERVAL 10 HOUR + INTERVAL k.n MINUTE
  FROM base_day d JOIN base_n k ON k.n <= 10
 WHERE d.dn = 8
    ON DUPLICATE KEY UPDATE impression_log.serve_id = impression_log.serve_id;

-- 스와이프: 유효 노출마다 1건, 찜 30% 패스 70%, 노출 10초 뒤
INSERT INTO swipe_log (serve_id, member_id, business_date, campaign_id, category_code, action, occurred_at,
                       created_at, updated_at)
SELECT UNHEX(CONCAT('000000000001', '7000', '8', LPAD(HEX(v.s), 15, '0'))), 10000000 + v.m, v.bdate, 10000000 + v.n,
       c.cat_code, IF(v.wish, 'WISH', 'PASS'),
       v.served_at + INTERVAL 10 SECOND, v.served_at + INTERVAL 10 SECOND, v.served_at + INTERVAL 10 SECOND
  FROM base_serve v JOIN base_cmp c ON c.n = v.n
 WHERE v.valid
    ON DUPLICATE KEY UPDATE swipe_log.serve_id = swipe_log.serve_id;

/*
 * ADS.WISHLIST: 찜 스와이프마다 (회원, 캠페인) 1행.
 * 오늘 찜도 있다.
 */
INSERT INTO wishlist (wishlist_id, member_id, campaign_id, store_id, serve_id, campaign_ended_at, created_at, updated_at)
SELECT 10000000 + s, 10000000 + m, 10000000 + n, 10000000 + n,
       UNHEX(CONCAT('000000000001', '7000', '8', LPAD(HEX(s), 15, '0'))), NULL,
       served_at + INTERVAL 10 SECOND, served_at + INTERVAL 10 SECOND
  FROM base_serve
 WHERE wish
    ON DUPLICATE KEY UPDATE wishlist.wishlist_id = wishlist.wishlist_id;

/*
 * ADS.WISHLIST_ENDED, ADS.WISHLIST_ENDED_OLD: 끝난 캠페인(B+1060 가게 60, B+1120 가게 120)의 찜.
 * 공식상 ENDED 캠페인은 D-14 전에 끝나므로 종료 시각은 묶음이 요구하는 D-1 12:00 과 D-4 12:00 으로 둔다.
 */
INSERT INTO wishlist (wishlist_id, member_id, campaign_id, store_id, serve_id, campaign_ended_at, created_at, updated_at)
SELECT 10000000 + 900000 + w.k, 10000000 + w.m, 10000000 + w.c, 10000000 + w.st, NULL, w.ended_at,
       w.ended_at - INTERVAL 2 DAY, w.ended_at
  FROM (
    SELECT 1 AS k, 2 AS m, 1060 AS c, 60 AS st, TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 1 DAY), '12:00:00') AS ended_at
    UNION ALL SELECT 2, 3, 1060, 60, TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 1 DAY), '12:00:00')
    UNION ALL SELECT 3, 2, 1120, 120, TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 4 DAY), '12:00:00')
  ) w
    ON DUPLICATE KEY UPDATE wishlist.wishlist_id = wishlist.wishlist_id;

/*
 * 가게 상세에서 오늘 아침 누른 찜 (serve_id NULL).
 * 회원 2 와 3 이 캠페인 150 을 찜했다.
 */
INSERT INTO wishlist (wishlist_id, member_id, campaign_id, store_id, serve_id, campaign_ended_at, created_at, updated_at)
SELECT 10000000 + 900010 + k.n, 10000000 + 1 + k.n, 10000000 + 150, 10000000 + 150, NULL, NULL,
       TIMESTAMP('${baseDate}', '09:30:00'), TIMESTAMP('${baseDate}', '09:30:00')
  FROM base_n k WHERE k.n <= 2
    ON DUPLICATE KEY UPDATE wishlist.wishlist_id = wishlist.wishlist_id;

-- 차감 판정: 유효 노출마다 BILLED, 단가 10
INSERT INTO deduction_result (deduction_result_id, serve_id, campaign_id, owner_id, business_date, billing_status,
                              unit_price, created_at, updated_at)
SELECT 10000000 + s, UNHEX(CONCAT('000000000001', '7000', '8', LPAD(HEX(s), 15, '0'))), 10000000 + n, 10000000 + n,
       bdate, 'BILLED', 10, served_at + INTERVAL 2 SECOND, served_at + INTERVAL 2 SECOND
  FROM base_serve
 WHERE valid
    ON DUPLICATE KEY UPDATE deduction_result.deduction_result_id = deduction_result.deduction_result_id;

-- 원장 DEDUCT: 유효 노출마다 -10 (B+1100000 + s)
INSERT INTO point_ledger (point_ledger_id, owner_id, campaign_id, serve_id, payment_id, refund_request_id, business_date,
                          adjust_target_date, entry_type, source, amount, reason, idempotency_key, created_at, updated_at)
SELECT 10000000 + 1100000 + s, 10000000 + n, 10000000 + n, UNHEX(CONCAT('000000000001', '7000', '8', LPAD(HEX(s), 15, '0'))),
       NULL, NULL, NULL, NULL, 'DEDUCT', 'REALTIME', -10, NULL, NULL,
       served_at + INTERVAL 2 SECOND, served_at + INTERVAL 2 SECOND
  FROM base_serve
 WHERE valid
    ON DUPLICATE KEY UPDATE point_ledger.point_ledger_id = point_ledger.point_ledger_id;

-- ADS.FEED_VISIT_7D: 서빙받은 회원의 날짜별 첫 피드 위치
INSERT INTO feed_visit_daily (business_date, member_id, lat_rounded, lng_rounded, created_at, updated_at)
SELECT bdate, 10000000 + m,
       ROUND(ROUND(ELT(m % 3 + 1, 37.4979, 37.5006, 37.5045) + ((m * 13) % 1500) * COS(RADIANS((m * 71) % 360)) / 111000, 7), 3),
       ROUND(ROUND(ELT(m % 3 + 1, 127.0276, 127.0364, 127.0490) + ((m * 13) % 1500) * SIN(RADIANS((m * 71) % 360)) / 88200, 7), 3),
       MIN(served_at), MIN(served_at)
  FROM base_serve
 GROUP BY bdate, m
    ON DUPLICATE KEY UPDATE feed_visit_daily.member_id = feed_visit_daily.member_id;

/*
 * ADS.FEED_FILTER_SET: 회원 2801 ~ 2840 은 정렬(짝수 DISCOUNT_RATE, 홀수 DISTANCE)과 업종 m % 4 개(0 이면 전체)를 저장했다.
 * ADS.FEED_FILTER_OTHER_CATEGORY: 회원 2841 ~ 2850 은 JAPANESE 만 골랐다.
 */
INSERT INTO feed_filter (member_id, sort_type, created_at, updated_at)
SELECT 10000000 + n, IF(n <= 2840 AND n % 2 = 0, 'DISCOUNT_RATE', 'DISTANCE'),
       TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 1 + n % 5 DAY), '12:00:00'),
       TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 1 + n % 5 DAY), '12:00:00')
  FROM base_n WHERE n BETWEEN 2801 AND 2850
    ON DUPLICATE KEY UPDATE feed_filter.member_id = feed_filter.member_id;

INSERT INTO feed_filter_category (member_id, category_code, created_at, updated_at)
SELECT 10000000 + m.n, IF(m.n > 2840, 'JAPANESE', c.code),
       TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 1 + m.n % 5 DAY), '12:00:00'),
       TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 1 + m.n % 5 DAY), '12:00:00')
  FROM base_n m
  JOIN base_k j ON j.n <= IF(m.n > 2840, 1, m.n % 4)
  JOIN base_cat c ON c.k = (m.n + j.n) % 9
 WHERE m.n BETWEEN 2801 AND 2850
    ON DUPLICATE KEY UPDATE feed_filter_category.member_id = feed_filter_category.member_id;

-- 캠페인 x 날짜 소진액 (유효 노출 x 10)
CREATE TEMPORARY TABLE base_spend (n INT NOT NULL, dn INT NOT NULL, spent BIGINT NOT NULL, PRIMARY KEY (n, dn));
INSERT INTO base_spend
SELECT n, dn, COUNT(*) * 10 FROM base_serve WHERE valid GROUP BY n, dn;

/*
 * 예산 계획: 기간 안의 ACTIVE 캠페인 x (D-7 ~ D), 단가 10, daily_budget = 설정 하루 예산(점주 잔액 300,000 이 늘 더 크다).
 * BIL.PLAN_D_PAUSED: 181 ~ 195(PAUSED OWNER)는 오늘 계획이 paused 이고 예약도 있다.
 * ADS.BUDGET_PLAN_TODAY, BIL.PLAN_D_INSUFFICIENT: 196 ~ 205(NO_POINTS, 잔액 0)는 오늘 daily_budget 0, paused, D 00:00 이다.
 */
CREATE TEMPORARY TABLE base_plan (
    n INT NOT NULL,
    dn INT NOT NULL,
    bdate DATE NOT NULL,
    d0 DATETIME(6) NOT NULL,
    owner_id BIGINT NOT NULL,
    daily_budget BIGINT NOT NULL,
    paused TINYINT NOT NULL,
    status_changed_at DATETIME(6) NULL,
    PRIMARY KEY (n, dn)
);
INSERT INTO base_plan
SELECT c.n, d.dn, d.bdate, d.d0, c.owner_id,
       IF(c.cstate = 'PAUSED_NO_POINTS', 0, c.budget),
       c.cstate <> 'ACTIVE',
       IF(c.cstate = 'ACTIVE', NULL, c.status_changed_at)
  FROM base_cmp c
  JOIN base_day d ON d.dn BETWEEN 1 AND 8 AND d.bdate BETWEEN c.start_date AND c.end_date
 WHERE c.cstate = 'ACTIVE' OR d.dn = 8;

INSERT INTO budget_daily_plan (budget_daily_plan_id, campaign_id, owner_id, business_date, daily_budget, unit_price, paused,
                               created_at, updated_at, status_changed_at)
SELECT 10000000 + n * 10 + dn, 10000000 + n, owner_id, bdate, daily_budget, 10, paused,
       d0, COALESCE(GREATEST(d0, status_changed_at), d0), status_changed_at
  FROM base_plan
    ON DUPLICATE KEY UPDATE budget_daily_plan.budget_daily_plan_id = budget_daily_plan.budget_daily_plan_id;

/*
 * 시간대별 목표는 광고 서빙이 그때그때 계산한다.
 * 그 표(budget_hourly_target)는 V8 이 지웠으므로 넣지 않는다 (#829, #858).
 */

/*
 * 원장 RESERVE: 예산이 있는 계획마다 그날 00:00 (B+1000000 + n * 10 + dn).
 * BIL.LEDGER_TODAY_RESERVED, BIL.PLAN_D: 오늘 RESERVE 와 오늘 아침 DEDUCT 가 있고 오늘 RELEASE 는 없다.
 */
INSERT INTO point_ledger (point_ledger_id, owner_id, campaign_id, serve_id, payment_id, refund_request_id, business_date,
                          adjust_target_date, entry_type, source, amount, reason, idempotency_key, created_at, updated_at)
SELECT 10000000 + 1000000 + n * 10 + dn, owner_id, 10000000 + n, NULL, NULL, NULL, bdate, NULL, 'RESERVE', 'REALTIME',
       -daily_budget, NULL, NULL, d0, d0
  FROM base_plan
 WHERE daily_budget > 0
    ON DUPLICATE KEY UPDATE point_ledger.point_ledger_id = point_ledger.point_ledger_id;

/*
 * 원장 RELEASE: 과거 날짜 계획마다 13:30 에 예약액 - 소진액 (B+1010000 + n * 10 + dn).
 * BIL.PLAN_D1: 캠페인 7 은 D-1 RELEASE 가 없다. BIL.LEDGER_UNRELEASED_PAST: 캠페인 8 은 D-2 RELEASE 가 없다.
 * BIL.PLAN_D1_RELEASED, BIL.LEDGER_SETTLED_DAYS: 나머지는 날마다 RESERVE, DEDUCT, RELEASE 가 짝을 이룬다.
 */
INSERT INTO point_ledger (point_ledger_id, owner_id, campaign_id, serve_id, payment_id, refund_request_id, business_date,
                          adjust_target_date, entry_type, source, amount, reason, idempotency_key, created_at, updated_at)
SELECT 10000000 + 1010000 + p.n * 10 + p.dn, p.owner_id, 10000000 + p.n, NULL, NULL, NULL, p.bdate, NULL, 'RELEASE', 'REALTIME',
       p.daily_budget - COALESCE(sp.spent, 0), NULL, NULL,
       p.d0 + INTERVAL 13 HOUR + INTERVAL 30 MINUTE, p.d0 + INTERVAL 13 HOUR + INTERVAL 30 MINUTE
  FROM base_plan p LEFT JOIN base_spend sp ON sp.n = p.n AND sp.dn = p.dn
 WHERE p.dn < 8 AND p.daily_budget > COALESCE(sp.spent, 0)
   AND NOT (p.n = 7 AND p.dn = 7) AND NOT (p.n = 8 AND p.dn = 6)
    ON DUPLICATE KEY UPDATE point_ledger.point_ledger_id = point_ledger.point_ledger_id;

-- BIL.LEDGER_STANDALONE: 같은 날 RESERVE 가 없는 단독 RELEASE +1000 (끝난 캠페인 B+1001, 가게와 점주 1, D-5)
INSERT INTO point_ledger (point_ledger_id, owner_id, campaign_id, serve_id, payment_id, refund_request_id, business_date,
                          adjust_target_date, entry_type, source, amount, reason, idempotency_key, created_at, updated_at)
VALUES (10000000 + 1019999, 10000000 + 1, 10000000 + 1001, NULL, NULL, NULL, DATE_SUB('${baseDate}', INTERVAL 5 DAY), NULL,
        'RELEASE', 'RECONCILIATION', 1000, NULL, NULL,
        TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 4 DAY)), TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 4 DAY)))
    ON DUPLICATE KEY UPDATE point_ledger.point_ledger_id = point_ledger.point_ledger_id;

/*
 * ADS.CANDIDATE_TODAY, ADS.CANDIDATES_MANY: 오늘 후보는 기간 안 ACTIVE 캠페인 전부 (SERVABLE, 00:00 적재).
 * 과거 후보는 D-2, D-1 만 남는다. ADS.CANDIDATE_OLD 로 캠페인 1 의 D-8, D-7 후보 2행을 더 둔다.
 * 과거 날짜에 쿠폰이 모두 나간 회차는 마지막 발급 시각을 sold_out_at 에 둔다 (아래 base_issue 뒤에 채운다).
 */
INSERT INTO ad_candidate (business_date, campaign_id, store_id, store_name, store_image_url, category_code, latitude, longitude,
                          target_radius, target_gender, target_age_groups, discount_target_type, discount_type,
                          discount_value, discount_menu_name, usable_start_time, usable_end_time, issue_quantity,
                          campaign_created_at, new_priority_until, campaign_state, state_changed_at, sold_out_at,
                          snapshot_read_at, created_at, updated_at)
SELECT d.bdate, c.campaign_id, c.store_id, c.store_name, c.store_image_url, c.cat_code, c.latitude, c.longitude,
       c.target_radius, c.target_gender, c.target_age_groups, c.dtarget, c.dtype, c.dvalue, c.menu_name, c.ustart, c.uend,
       c.qty, c.created_at, NULL, 'SERVABLE', c.status_changed_at, NULL, d.d0, d.d0, d.d0
  FROM base_cmp c
  JOIN base_day d ON d.bdate BETWEEN c.start_date AND c.end_date
 WHERE c.cstate = 'ACTIVE' AND (d.dn IN (6, 7, 8) OR (c.n = 1 AND d.dn IN (0, 1)))
    ON DUPLICATE KEY UPDATE ad_candidate.campaign_id = ad_candidate.campaign_id;

/*
 * 쿠폰 회차: 기간 안 ACTIVE 캠페인 x (D-7 ~ D), coupon_event_id = B + n * 10 + dn. 03:00 준비, 재고 = 수량.
 * 발급 마감 배치는 회차 상태를 바꾸지 않으므로 과거 회차도 ACTIVE 로 남는다 (CPN.EVENT_D1_CONSISTENT, CPN.COUPON_USED).
 * CPN.EVENT_ACTIVE: 오늘 회차는 ACTIVE 이고 재고가 모두 AVAILABLE 이다.
 * CPN.EVENT_D_PAUSED: 181 ~ 195(PAUSED OWNER)의 오늘 회차는 PAUSED 다.
 */
CREATE TEMPORARY TABLE base_event (
    n INT NOT NULL,
    dn INT NOT NULL,
    event_id BIGINT NOT NULL,
    bdate DATE NOT NULL,
    qty INT NOT NULL,
    ustart_at DATETIME(6) NOT NULL,
    uend_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (n, dn)
);
INSERT INTO base_event
SELECT c.n, d.dn, 10000000 + c.n * 10 + d.dn, d.bdate, c.qty, TIMESTAMP(d.bdate, c.ustart), TIMESTAMP(d.bdate, c.uend),
       d.d0 + INTERVAL 3 HOUR
  FROM base_cmp c
  JOIN base_day d ON d.dn BETWEEN 1 AND 8 AND d.bdate BETWEEN c.start_date AND c.end_date
 WHERE c.cstate = 'ACTIVE' OR (c.cstate = 'PAUSED_OWNER' AND d.dn = 8);

INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity, usable_start_at, usable_end_at, status,
                          status_changed_at, created_at, updated_at, store_id, store_name, store_image_url,
                          discount_target_type, discount_type, discount_value, target_menu_id, target_menu_name,
                          target_menu_price, target_menu_discounted_price)
SELECT e.event_id, c.campaign_id, e.bdate, e.qty, e.ustart_at, e.uend_at, IF(c.cstate = 'ACTIVE', 'ACTIVE', 'PAUSED'),
       c.status_changed_at, e.created_at, e.created_at, c.store_id, c.store_name, c.store_image_url,
       c.dtarget, c.dtype, c.dvalue, c.menu_id, c.menu_name, c.menu_price, c.menu_disc_price
  FROM base_event e JOIN base_cmp c ON c.n = e.n
    ON DUPLICATE KEY UPDATE coupon_event.coupon_event_id = coupon_event.coupon_event_id;

/*
 * 발급: 과거 날짜에 찜한 노출의 절반이 찜 1분 뒤 발급을 요청한다.
 * 회원은 하루 3장까지(먼저 찜한 순), 회차는 재고 수량까지(회원 1 을 먼저, 그다음 요청 순) 받는다.
 * 받은 순서가 재고 sequence_no 이고 그 재고의 coupon_code 를 회원 쿠폰에 그대로 쓴다.
 */
CREATE TEMPORARY TABLE base_issue1 AS
SELECT q.s, q.dn, q.m, q.n, q.heavy, q.served_at + INTERVAL 1 MINUTE AS issued_at
  FROM (
    SELECT v.*, ROW_NUMBER() OVER (PARTITION BY v.m, v.dn ORDER BY v.served_at, v.s) AS rk
      FROM base_serve v
     WHERE v.issue_cand
  ) q
 WHERE q.rk <= 3;

CREATE TEMPORARY TABLE base_issue (
    s INT PRIMARY KEY,
    dn INT NOT NULL,
    m INT NOT NULL,
    n INT NOT NULL,
    seq_no INT NOT NULL,
    event_id BIGINT NOT NULL,
    mc_id BIGINT NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    ustart_at DATETIME(6) NOT NULL,
    uend_at DATETIME(6) NOT NULL,
    mstatus VARCHAR(20) NOT NULL,
    used_at DATETIME(6) NULL,
    UNIQUE KEY uk_base_issue_event_seq (event_id, seq_no),
    KEY idx_base_issue_md (m, dn)
);
INSERT INTO base_issue
SELECT r.s, r.dn, r.m, r.n, r.seq_no, r.event_id, 10000000 + (r.n * 10 + r.dn) * 1000 + r.seq_no, r.issued_at,
       r.ustart_at, r.uend_at,
       CASE WHEN r.heavy THEN IF(r.s % 10 = 3 AND r.dn % 2 = 1, 'EXPIRED', 'USED')
            WHEN CRC32(CONCAT('mc', r.s)) % 10 < 7 THEN 'USED'
            WHEN CRC32(CONCAT('mc', r.s)) % 10 < 9 THEN 'EXPIRED'
            ELSE 'REMOVED' END,
       GREATEST(r.issued_at + INTERVAL 5 MINUTE, r.ustart_at + INTERVAL 10 MINUTE)
  FROM (
    SELECT t.*, e.event_id, e.qty, e.ustart_at, e.uend_at,
           ROW_NUMBER() OVER (PARTITION BY t.n, t.dn ORDER BY t.heavy DESC, t.issued_at, t.s) AS seq_no
      FROM base_issue1 t JOIN base_event e ON e.n = t.n AND e.dn = t.dn
  ) r
 WHERE r.seq_no <= r.qty;

UPDATE base_issue SET used_at = NULL WHERE mstatus <> 'USED';

/*
 * 재고: 회차마다 sequence_no 1 ~ 수량.
 * 발급된 순번은 ISSUED, 나머지는 AVAILABLE.
 */
INSERT INTO coupon_inventory (coupon_inventory_id, coupon_event_id, sequence_no, coupon_code, status, usable_start_at,
                              usable_end_at, created_at, updated_at)
SELECT 10000000 + (e.n * 10 + e.dn) * 1000 + k.n, e.event_id, k.n,
       UNHEX(CONCAT('000000000003', '7000', '8', LPAD(HEX((e.n * 10 + e.dn) * 1000 + k.n), 15, '0'))),
       IF(x.s IS NULL, 'AVAILABLE', 'ISSUED'), e.ustart_at, e.uend_at, e.created_at, COALESCE(x.issued_at, e.created_at)
  FROM base_event e
  JOIN base_k k ON k.n <= e.qty
  LEFT JOIN base_issue x ON x.event_id = e.event_id AND x.seq_no = k.n
    ON DUPLICATE KEY UPDATE coupon_inventory.coupon_inventory_id = coupon_inventory.coupon_inventory_id;

/*
 * 회원 쿠폰: 과거 발급의 70% USED, 20% EXPIRED, 10% REMOVED.
 * CPN.COUPON_USED, CPN.COUPON_EXPIRED, CPN.EVENT_D1_CONSISTENT, CPN.HISTORY_MANY 를 만든다.
 * USED 는 QR 을 한 번 받아 20초 뒤 사용했다(qr_version 1, 만료 40초 뒤). REMOVED 는 삭제로 qr_version 이 1 오르고 토큰이 비었다.
 */
INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code, qr_version, qr_token, qr_expires_at,
                           status, usable_start_at, usable_end_at, created_at, updated_at)
SELECT mc_id, event_id, 10000000 + m,
       UNHEX(CONCAT('000000000003', '7000', '8', LPAD(HEX((n * 10 + dn) * 1000 + seq_no), 15, '0'))),
       IF(mstatus IN ('USED', 'REMOVED'), 1, 0),
       IF(mstatus = 'USED', UNHEX(CONCAT('000000000004', '7000', '8', LPAD(HEX((n * 10 + dn) * 1000 + seq_no), 15, '0'))), NULL),
       IF(mstatus = 'USED', used_at + INTERVAL 40 SECOND, NULL),
       mstatus, ustart_at, uend_at, issued_at,
       CASE mstatus WHEN 'USED' THEN used_at WHEN 'EXPIRED' THEN uend_at + INTERVAL 5 MINUTE
                    WHEN 'REMOVED' THEN issued_at + INTERVAL 10 MINUTE ELSE issued_at END
  FROM base_issue
    ON DUPLICATE KEY UPDATE member_coupon.member_coupon_id = member_coupon.member_coupon_id;

-- 사용 내역: USED 마다 1행, 가게는 회차의 가게, 할인액은 메뉴 차액 또는 정액
INSERT INTO coupon_usage_history (coupon_usage_history_id, member_coupon_id, store_id, discount_amount, created_at, updated_at)
SELECT x.mc_id, x.mc_id, c.store_id, c.discount_amount, x.used_at, x.used_at
  FROM base_issue x JOIN base_cmp c ON c.n = x.n
 WHERE x.mstatus = 'USED'
    ON DUPLICATE KEY UPDATE coupon_usage_history.coupon_usage_history_id = coupon_usage_history.coupon_usage_history_id;

-- 일일 한도: 회원 x 발급일, issued_count 는 그날 받은 쿠폰 중 삭제하지 않은 수 (쿠폰 정합성 검사와 같은 기준, 0 ~ 3)
INSERT INTO coupon_daily_limit (coupon_daily_limit_id, member_id, issued_count, business_date, created_at, updated_at)
SELECT 10000000 + x.dn * 10000 + x.m, 10000000 + x.m, SUM(x.mstatus <> 'REMOVED'), d.bdate, MIN(x.issued_at),
       MAX(IF(x.mstatus = 'REMOVED', x.issued_at + INTERVAL 10 MINUTE, x.issued_at))
  FROM base_issue x JOIN base_day d ON d.dn = x.dn
 GROUP BY x.dn, x.m, d.bdate
    ON DUPLICATE KEY UPDATE coupon_daily_limit.coupon_daily_limit_id = coupon_daily_limit.coupon_daily_limit_id;

-- 과거 후보(D-2, D-1) 중 회차 재고가 다 나간 것은 마지막 발급 시각을 소진 시각으로 둔다
CREATE TEMPORARY TABLE base_sold_out (n INT NOT NULL, dn INT NOT NULL, sold_at DATETIME(6) NOT NULL, PRIMARY KEY (n, dn));
INSERT INTO base_sold_out
SELECT x.n, x.dn, MAX(x.issued_at)
  FROM base_issue x JOIN base_event e ON e.event_id = x.event_id
 WHERE x.dn IN (6, 7)
 GROUP BY x.n, x.dn, e.qty
HAVING COUNT(*) = e.qty;

UPDATE ad_candidate a
  JOIN base_sold_out o ON a.campaign_id = 10000000 + o.n
  JOIN base_day d ON d.dn = o.dn AND a.business_date = d.bdate
   SET a.sold_out_at = o.sold_at, a.updated_at = o.sold_at;

DROP TEMPORARY TABLE base_sold_out;
DROP TEMPORARY TABLE base_issue;
DROP TEMPORARY TABLE base_issue1;
DROP TEMPORARY TABLE base_event;
DROP TEMPORARY TABLE base_plan;
DROP TEMPORARY TABLE base_spend;
DROP TEMPORARY TABLE base_serve;
DROP TEMPORARY TABLE base_cmp;
DROP TEMPORARY TABLE base_cat;
DROP TEMPORARY TABLE base_day;
DROP TEMPORARY TABLE base_k;
DROP TEMPORARY TABLE base_n;
