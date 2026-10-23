/*
 * 기본 세계의 캠페인과 가게 캠페인 요약을 넣는다 (#741, WORLD_SPEC 캠페인 (V4)).
 * 템플릿과 포스터는 받은 디자인으로 바꿀 수 있게 반복 마이그레이션 R__templates_posters.sql 이 넣는다 (#858).
 * 번호는 B = 10000000 을 더한 고정 번호다.
 * 가게와 메뉴 값은 V3 의 공식으로 다시 계산해 넣고 V3 의 행을 읽지 않는다.
 * 예외로 store_campaign_summary 는 store, store_menu 에 외래 키가 있어 그 행이 있을 때만 넣는다.
 */

-- 숫자 표 1 ~ 1000 을 숫자 열 교차 조인으로 만든다
CREATE TEMPORARY TABLE base_n (n INT PRIMARY KEY);
INSERT INTO base_n (n)
SELECT a.d + b.d * 10 + c.d * 100 + 1
FROM (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a
CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b
CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) c;


/*
 * 캠페인 값을 먼저 임시 표에 계산하고 campaign, campaign_status_log 에 나눠 넣는다.
 * n 은 campaign_id - B 이고 s 는 가게 번호다.
 * 할인, 대상, 수량, 사용 시간, 하루 예산 공식의 n 은 캠페인 번호를 쓰고, 메뉴가 있는지는 가게 s 로 본다.
 */
CREATE TEMPORARY TABLE base_cmp (
    n INT PRIMARY KEY,
    s INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    paused_reason VARCHAR(20) NULL,
    stage INT NULL,
    start_date DATE NULL,
    end_date DATE NULL,
    status_changed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    target_type VARCHAR(20) NULL,
    discount_type VARCHAR(20) NULL,
    discount_value INT NULL,
    target_menu_id BIGINT NULL,
    qty INT NULL,
    usable_start TIME NULL,
    usable_end TIME NULL,
    radius INT NULL,
    gender VARCHAR(10) NULL,
    ages VARCHAR(50) NULL,
    budget BIGINT NULL,
    store_name VARCHAR(50) NULL,
    menu_name VARCHAR(50) NULL
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

/*
 * CMP.ACTIVE, CMP.ACTIVE_MANY, CMP.ACTIVE_MENU: 1 ~ 180 과 301 ~ 330 (#858) 은 D-14 ~ D+14. 세 역에 70개씩이다.
 * CMP.ACTIVE_FIRST_DAY: 179 는 D 에 시작한다.
 * CMP.ENDS_TODAY: 178 은 D 에 끝난다.
 * CMP.ACTIVE_PAST_END: 242 는 D-1 에 끝났는데 ACTIVE 로 남았다.
 */
INSERT INTO base_cmp (n, s, status, start_date, end_date, status_changed_at, created_at)
SELECT n, n, 'ACTIVE',
       CASE WHEN n = 179 THEN DATE('${baseDate}') ELSE DATE_SUB(DATE('${baseDate}'), INTERVAL 14 DAY) END,
       CASE WHEN n = 178 THEN DATE('${baseDate}') WHEN n = 242 THEN DATE_SUB(DATE('${baseDate}'), INTERVAL 1 DAY)
            ELSE DATE_ADD(DATE('${baseDate}'), INTERVAL 14 DAY) END,
       TIMESTAMP(CASE WHEN n = 179 THEN DATE('${baseDate}') ELSE DATE_SUB(DATE('${baseDate}'), INTERVAL 14 DAY) END, '00:00:00'),
       TIMESTAMP(CASE WHEN n = 179 THEN DATE_SUB(DATE('${baseDate}'), INTERVAL 3 DAY) ELSE DATE_SUB(DATE('${baseDate}'), INTERVAL 17 DAY) END, '10:00:00')
FROM base_n
WHERE n <= 180 OR n = 242 OR n BETWEEN 301 AND 330;

-- CMP.PAUSED_OWNER (181 ~ 195), CMP.PAUSED_NO_POINTS (196 ~ 205), CMP.PAUSED_ADMIN (206 ~ 210)
INSERT INTO base_cmp (n, s, status, paused_reason, start_date, end_date, status_changed_at, created_at)
SELECT n, n, 'PAUSED',
       CASE WHEN n <= 195 THEN 'OWNER' WHEN n <= 205 THEN 'NO_POINTS' ELSE 'ADMIN' END,
       DATE_SUB(DATE('${baseDate}'), INTERVAL 14 DAY), DATE_ADD(DATE('${baseDate}'), INTERVAL 14 DAY),
       CASE WHEN n <= 195 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 8 DAY), '14:00:00')
            WHEN n <= 205 THEN TIMESTAMP(DATE('${baseDate}'), '00:00:00')
            ELSE TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 8 DAY), '10:00:00') END,
       TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 17 DAY), '10:00:00')
FROM base_n
WHERE n BETWEEN 181 AND 210;

/*
 * CMP.SCHEDULED: 211 ~ 220 은 D+1 ~ D+5 에 시작해 7일 집행한다.
 * CMP.SCHEDULED_START_REACHED: 221 은 D-1 에 시작했는데 SCHEDULED 로 남았다.
 */
INSERT INTO base_cmp (n, s, status, start_date, end_date, status_changed_at, created_at)
SELECT n, n, 'SCHEDULED',
       CASE WHEN n = 221 THEN DATE_SUB(DATE('${baseDate}'), INTERVAL 1 DAY) ELSE DATE_ADD(DATE('${baseDate}'), INTERVAL (n - 211) % 5 + 1 DAY) END,
       CASE WHEN n = 221 THEN DATE_ADD(DATE('${baseDate}'), INTERVAL 5 DAY) ELSE DATE_ADD(DATE('${baseDate}'), INTERVAL (n - 211) % 5 + 7 DAY) END,
       TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL CASE WHEN n = 221 THEN 2 ELSE 1 END DAY), '15:00:00'),
       TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL CASE WHEN n = 221 THEN 4 ELSE 3 END DAY), '10:00:00')
FROM base_n
WHERE n BETWEEN 211 AND 221;

/*
 * DRAFT 222 ~ 241, 단계는 (n - 222) % 4 다.
 * CMP.DRAFT_STEP1: 단계 0 은 쿠폰 조건만 있고 예산과 기간이 NULL 이다.
 * 단계 1 은 노출 대상까지 저장했고 예산과 기간이 NULL 이다.
 * CMP.DRAFT_PERIOD_SET, CMP.OTHER_OWNER: 단계 2 는 예산과 기간 D+1 ~ D+7 까지 저장했다.
 * CMP.DRAFT_START_PASSED: 240 (단계 2) 은 저장한 시작일 D-1 이 지났다.
 * CMP.DRAFT_LEGACY_BLOCKED: 238 (단계 0) 은 전체 메뉴 + 퍼센트 10 으로 D-30 에 저장했다.
 */
INSERT INTO base_cmp (n, s, status, stage, start_date, end_date, status_changed_at, created_at)
SELECT n, n, 'DRAFT', (n - 222) % 4,
       CASE WHEN (n - 222) % 4 < 2 THEN NULL WHEN n = 240 THEN DATE_SUB(DATE('${baseDate}'), INTERVAL 1 DAY)
            ELSE DATE_ADD(DATE('${baseDate}'), INTERVAL 1 DAY) END,
       CASE WHEN (n - 222) % 4 < 2 THEN NULL WHEN n = 240 THEN DATE_ADD(DATE('${baseDate}'), INTERVAL 5 DAY)
            ELSE DATE_ADD(DATE('${baseDate}'), INTERVAL 7 DAY) END,
       TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL CASE WHEN n = 238 THEN 30 ELSE 1 END DAY), '10:00:00'),
       TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL CASE WHEN n = 238 THEN 30 ELSE 1 END DAY), '10:00:00')
FROM base_n
WHERE n BETWEEN 222 AND 241;

-- CMP.ENDED: B+1000+k 는 가게 (k - 1) % 250 + 1 에서 D-(20 + k % 60) 부터 7일 집행하고 끝났다
INSERT INTO base_cmp (n, s, status, start_date, end_date, status_changed_at, created_at)
SELECT 1000 + n, (n - 1) % 250 + 1, 'ENDED',
       DATE_SUB(DATE('${baseDate}'), INTERVAL 20 + n % 60 DAY),
       DATE_SUB(DATE('${baseDate}'), INTERVAL 14 + n % 60 DAY),
       CASE WHEN n % 5 = 0 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 14 + n % 60 DAY), '13:00:00')
            ELSE TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 13 + n % 60 DAY), '00:00:00') END,
       TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 23 + n % 60 DAY), '10:00:00')
FROM base_n
WHERE n <= 300;

/*
 * CMP.ENDED_MANY: 가게 120 에 끝난 캠페인 25개를 더한다 (B+2001 ~ B+2025).
 * D-200 부터 7일씩 겹치지 않게 두고, 공식의 ENDED (B+1120, D-20 ~ D-14) 와도 겹치지 않는다.
 */
INSERT INTO base_cmp (n, s, status, start_date, end_date, status_changed_at, created_at)
SELECT 2000 + n, 120, 'ENDED',
       DATE_SUB(DATE('${baseDate}'), INTERVAL 200 - (n - 1) * 7 DAY),
       DATE_SUB(DATE('${baseDate}'), INTERVAL 194 - (n - 1) * 7 DAY),
       CASE WHEN n % 5 = 0 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 194 - (n - 1) * 7 DAY), '13:00:00')
            ELSE TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 193 - (n - 1) * 7 DAY), '00:00:00') END,
       TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 203 - (n - 1) * 7 DAY), '10:00:00')
FROM base_n
WHERE n <= 25;

-- CMP.MULTI_PER_OWNER: 가게 177 (ACTIVE 177) 에 PAUSED OWNER 캠페인 하나를 더한다 (B+2101)
INSERT INTO base_cmp (n, s, status, paused_reason, start_date, end_date, status_changed_at, created_at)
VALUES (2101, 177, 'PAUSED', 'OWNER', DATE_SUB(DATE('${baseDate}'), INTERVAL 14 DAY), DATE_ADD(DATE('${baseDate}'), INTERVAL 14 DAY),
        TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 8 DAY), '14:00:00'), TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 17 DAY), '10:00:00'));

/*
 * 쿠폰 조건, 대상, 예산 공식 (WORLD_SPEC 캠페인 (V4) 아래 목록).
 * 메뉴 할인은 n % 4 = 2 일 때만 나오므로 정률과 정액은 n % 8 로 가른다 (6 이면 정액 2000).
 */
UPDATE base_cmp
SET target_type = CASE WHEN s % 4 > 0 AND n % 2 = 0 THEN 'MENU' ELSE 'ALL' END,
    discount_type = CASE WHEN s % 4 > 0 AND n % 2 = 0 AND n % 8 <> 6 THEN 'PERCENT' ELSE 'AMOUNT' END,
    discount_value = CASE WHEN s % 4 > 0 AND n % 2 = 0 THEN IF(n % 8 = 6, 2000, 10 + n % 3 * 5)
                          ELSE 1000 + n % 5 * 1000 END,
    target_menu_id = CASE WHEN s % 4 > 0 AND n % 2 = 0 THEN 10000000 + s * 10 + 1 END,
    qty = CASE WHEN n % 17 = 0 THEN 3 ELSE ELT(n % 5 + 1, 10, 20, 30, 50, 100) END,
    usable_start = ELT(n % 4 + 1, '11:30:00', '12:00:00', '11:30:00', '13:00:00'),
    usable_end = ELT(n % 4 + 1, '13:30:00', '14:00:00', '15:00:00', '15:00:00'),
    radius = CASE WHEN n % 3 = 0 OR stage = 0 THEN 1000 ELSE ELT(n % 4 + 1, 500, 1000, 2000, 3000) END,
    gender = CASE WHEN n % 3 = 0 OR stage = 0 THEN 'ALL' ELSE ELT(n % 3 + 1, 'ALL', 'MALE', 'FEMALE') END,
    ages = CASE WHEN n % 3 = 0 OR stage = 0 THEN 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS'
                ELSE ELT(n % 4 + 1, 'AGE_20S', 'AGE_20S,AGE_30S', 'AGE_20S,AGE_30S,AGE_40S',
                         'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS') END,
    budget = CASE WHEN stage IN (0, 1) THEN NULL ELSE ELT(n % 4 + 1, 5000, 10000, 20000, 30000) END,
    store_name = CONCAT(ELT(s % 9 + 1, '한식', '중식', '일식', '양식', '분식', '아시안', '패스트푸드', '카페디저트', '기타'),
                        ' ', LPAD(s, 3, '0'), '호점'),
    menu_name = CONCAT(ELT(s % 9 + 1, '한식', '중식', '일식', '양식', '분식', '아시안', '패스트푸드', '카페디저트', '기타'),
                       ' 메뉴 1');

-- CMP.DRAFT_LEGACY_BLOCKED: 지금은 막힌 전체 메뉴 + 퍼센트 조합
UPDATE base_cmp
SET target_type = 'ALL', discount_type = 'PERCENT', discount_value = 10, target_menu_id = NULL
WHERE n = 238;

INSERT INTO campaign (campaign_id, owner_id, store_id, status, status_changed_at, paused_reason, discount_target_type,
                      discount_type, discount_value, target_menu_id, issue_quantity, usable_start_time,
                      usable_end_time, min_order_amount, notice, target_radius, target_gender, target_age_groups,
                      daily_budget, pending_daily_budget, start_date, end_date, created_at, updated_at)
SELECT 10000000 + n, 10000000 + s, 10000000 + s, status, status_changed_at, paused_reason, target_type,
       discount_type, discount_value, target_menu_id, qty, usable_start, usable_end, NULL, NULL, radius, gender,
       ages, budget, NULL, start_date, end_date, created_at, status_changed_at
FROM base_cmp
ON DUPLICATE KEY UPDATE campaign_id = campaign.campaign_id;

/*
 * 상태 기록. j = 1 DRAFT -> SCHEDULED (SYSTEM), j = 2 SCHEDULED -> ACTIVE (SYSTEM).
 * PAUSED 는 j = 3 ACTIVE -> PAUSED 이고 행위자는 사유를 따른다 (ADMIN, OWNER, NO_POINTS 는 SYSTEM).
 * ENDED 는 j = 3 ACTIVE -> ENDED (SYSTEM, 종료일 다음 00:00) 이고, n % 5 = 0 이면 점주가 일시중지 뒤 직접 끝냈다 (j = 3 ACTIVE -> PAUSED, j = 4 PAUSED -> ENDED, OWNER).
 * DRAFT 는 기록이 없다.
 */
INSERT INTO campaign_status_log (campaign_status_log_id, campaign_id, from_status, to_status, actor_type, reason,
                                 created_at, updated_at)
SELECT src.id, src.campaign_id, src.from_status, src.to_status, src.actor_type, src.reason, src.at, src.at
FROM (
    SELECT 10000000 + c.n * 10 + x.j AS id,
           10000000 + c.n AS campaign_id,
           CASE x.j WHEN 1 THEN 'DRAFT' WHEN 2 THEN 'SCHEDULED' WHEN 3 THEN 'ACTIVE' ELSE 'PAUSED' END AS from_status,
           CASE WHEN x.j = 1 THEN 'SCHEDULED'
                WHEN x.j = 2 THEN 'ACTIVE'
                WHEN x.j = 3 AND (c.status = 'PAUSED' OR c.n % 5 = 0) THEN 'PAUSED'
                ELSE 'ENDED' END AS to_status,
           CASE WHEN x.j <= 2 THEN 'SYSTEM'
                WHEN c.status = 'PAUSED' THEN IF(c.paused_reason = 'NO_POINTS', 'SYSTEM', c.paused_reason)
                WHEN c.n % 5 = 0 THEN 'OWNER'
                ELSE 'SYSTEM' END AS actor_type,
           CASE WHEN x.j = 3 AND c.paused_reason = 'ADMIN' THEN '부정 노출 확인'
                WHEN x.j = 3 AND c.paused_reason = 'NO_POINTS' THEN '잔액 부족' END AS reason,
           CASE WHEN x.j = 1 AND c.status = 'SCHEDULED' THEN c.status_changed_at
                WHEN x.j = 1 THEN TIMESTAMP(DATE_SUB(c.start_date, INTERVAL 1 DAY), '15:00:00')
                WHEN x.j = 2 THEN TIMESTAMP(c.start_date, '00:00:00')
                WHEN x.j = 3 AND c.status = 'ENDED' AND c.n % 5 = 0 THEN TIMESTAMP(c.end_date, '12:00:00')
                ELSE c.status_changed_at END AS at
    FROM base_cmp c
    JOIN (SELECT 1 j UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) x
    WHERE c.status <> 'DRAFT'
      AND (x.j = 1
           OR (x.j = 2 AND c.status <> 'SCHEDULED')
           OR (x.j = 3 AND c.status IN ('PAUSED', 'ENDED'))
           OR (x.j = 4 AND c.status = 'ENDED' AND c.n % 5 = 0))
) src
ON DUPLICATE KEY UPDATE campaign_status_log_id = campaign_status_log.campaign_status_log_id;


/*
 * CMP.ACTIVE, CMP.ACTIVE_MENU, STO.NO_CAMPAIGN: 서빙 대상 캠페인 (ACTIVE 1 ~ 180, 242, 301 ~ 330) 의 D-7 ~ D+1 날짜 중 기간 안인 날마다 요약 1행.
 * store_campaign_summary_id = B + n * 10 + (날짜 - D + 8) 이다.
 * store, store_menu 에 외래 키가 있어 V3 의 가게와 메뉴 행이 있을 때만 넣는다. V3 없이 돌리면 0 행이다.
 */
INSERT INTO store_campaign_summary (store_campaign_summary_id, store_id, campaign_id, business_date, campaign_status,
                                    discount_target_type, discount_type, discount_value, target_menu_id,
                                    usable_start_time, usable_end_time, created_at, updated_at)
SELECT 10000000 + c.n * 10 + x.n, st.store_id, 10000000 + c.n, DATE_ADD(DATE('${baseDate}'), INTERVAL x.n - 8 DAY), 'ACTIVE',
       c.target_type, c.discount_type, c.discount_value, c.target_menu_id, c.usable_start, c.usable_end,
       TIMESTAMP(DATE_ADD(DATE('${baseDate}'), INTERVAL x.n - 9 DAY), '23:50:00'),
       TIMESTAMP(DATE_ADD(DATE('${baseDate}'), INTERVAL x.n - 9 DAY), '23:50:00')
FROM base_cmp c
JOIN base_n x ON x.n BETWEEN 1 AND 9
JOIN store st ON st.store_id = 10000000 + c.s
LEFT JOIN store_menu m ON m.store_menu_id = c.target_menu_id
WHERE c.status = 'ACTIVE'
  AND DATE_ADD(DATE('${baseDate}'), INTERVAL x.n - 8 DAY) BETWEEN c.start_date AND c.end_date
  AND (c.target_menu_id IS NULL OR m.store_menu_id IS NOT NULL)
ON DUPLICATE KEY UPDATE store_campaign_summary_id = store_campaign_summary.store_campaign_summary_id;

DROP TEMPORARY TABLE base_cmp;
DROP TEMPORARY TABLE base_n;
