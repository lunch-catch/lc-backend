/*
 * 기본 세계의 템플릿, 캠페인, 포스터, 가게 캠페인 요약을 넣는다 (#741, WORLD_SPEC 템플릿 (V4), 캠페인 (V4)).
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
 * 템플릿 t 의 버전 v HTML 은 슬롯 4개를 하나씩 가진다.
 * 배경색만 (t + v) 로 바꾼다.
 */
CREATE TEMPORARY TABLE base_tpl_version (
    t INT NOT NULL,
    v INT NOT NULL,
    html TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (t, v)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- TPL.PUBLISHED_ACTIVE: t = 1 ~ 8 은 버전 2개, 게시 때 둘 다 soft delete
INSERT INTO base_tpl_version (t, v, html, created_at, deleted_at)
SELECT t.n, v.n, '', TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 12 DAY), '10:00:00') + INTERVAL v.n HOUR,
       TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 10 DAY), '10:00:00')
FROM base_n t
JOIN (SELECT 1 n UNION ALL SELECT 2) v
WHERE t.n BETWEEN 1 AND 8;

-- TPL.PUBLISHED_INACTIVE: t = 9, 10 은 버전 1개, 게시 때 soft delete
INSERT INTO base_tpl_version (t, v, html, created_at, deleted_at)
SELECT n, 1, '', TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 9 DAY), '10:00:00'),
       TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 8 DAY), '10:00:00')
FROM base_n
WHERE n IN (9, 10);

-- TPL.DRAFT: t = 11 은 버전 3개, D-3 ~ D-1 에 하나씩
INSERT INTO base_tpl_version (t, v, html, created_at, deleted_at)
SELECT 11, n, '', TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 4 - n DAY), '15:00:00'), NULL
FROM base_n
WHERE n BETWEEN 1 AND 3;

-- TPL.DRAFT_20_VERSIONS, TPL.DRAFT_25_VERSIONS: t = 12 는 버전 25개, 1 ~ 5 는 soft delete 되어 살아 있는 버전이 20개다
INSERT INTO base_tpl_version (t, v, html, created_at, deleted_at)
SELECT 12, n, '', TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 5 DAY), '09:00:00') + INTERVAL n * 2 HOUR,
       CASE WHEN n <= 5 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 2 DAY), '04:00:00') END
FROM base_n
WHERE n BETWEEN 1 AND 25;

UPDATE base_tpl_version
SET html = CONCAT('<div class="poster" style="background-color:',
                  ELT((t + v) % 8 + 1, '#FFF4E0', '#FFE3E3', '#E3F2FF', '#E8F7E8', '#F3E8FF', '#FFFBE0', '#E0FFF8', '#F0F0F0'),
                  ';color:#000000"><h1 data-slot="eventName">이벤트명</h1><p data-slot="discount">할인 내용</p>',
                  '<p data-slot="period">기간</p><img data-slot="image" alt="메뉴 이미지"></div>');

-- TPL.PUBLISHED_ACTIVE (1 ~ 8), TPL.PUBLISHED_INACTIVE (9, 10), TPL.DRAFT (11), TPL.DRAFT_20_VERSIONS, TPL.DRAFT_25_VERSIONS (12)
INSERT INTO template (template_id, name, status, html_content, active, published_at, published_version_number,
                      activated_by, activated_at, last_modified_by, last_modified_at, created_at, updated_at)
SELECT 10000000 + n,
       CONCAT('시나리오 템플릿 ', n),
       CASE WHEN n <= 10 THEN 'PUBLISHED' ELSE 'DRAFT' END,
       NULL,
       CASE WHEN n <= 8 THEN 1 ELSE 0 END,
       CASE WHEN n <= 8 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 10 DAY), '10:00:00')
            WHEN n <= 10 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 8 DAY), '10:00:00') END,
       CASE WHEN n <= 8 THEN 2 WHEN n <= 10 THEN 1 END,
       CASE WHEN n <= 8 THEN 10000001 END,
       CASE WHEN n <= 8 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 10 DAY), '10:05:00') END,
       10000001,
       CASE WHEN n <= 8 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 12 DAY), '12:00:00')
            WHEN n <= 10 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 9 DAY), '10:00:00')
            WHEN n = 11 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 1 DAY), '15:00:00')
            ELSE TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 5 DAY), '09:00:00') + INTERVAL 50 HOUR END,
       CASE WHEN n <= 8 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 12 DAY), '10:00:00')
            WHEN n <= 10 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 9 DAY), '10:00:00')
            WHEN n = 11 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 3 DAY), '15:00:00')
            ELSE TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 5 DAY), '09:00:00') END,
       CASE WHEN n <= 8 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 10 DAY), '10:05:00')
            WHEN n <= 10 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 8 DAY), '10:00:00')
            WHEN n = 11 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 1 DAY), '15:00:00')
            ELSE TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 5 DAY), '09:00:00') + INTERVAL 50 HOUR END
FROM base_n
WHERE n BETWEEN 1 AND 12
ON DUPLICATE KEY UPDATE template_id = template.template_id;

-- 게시된 템플릿은 게시에 쓴 버전의 HTML 을 복사해 둔다
UPDATE template tp
JOIN base_tpl_version tv ON tp.template_id = 10000000 + tv.t AND tv.v = tp.published_version_number
SET tp.html_content = tv.html
WHERE tp.template_id BETWEEN 10000001 AND 10000010;

INSERT INTO template_version (template_version_id, template_id, version_number, request_prompt, request_id,
                              html_content, message, created_at, updated_at, deleted_at)
SELECT 10000000 + t * 100 + v, 10000000 + t, v, '따뜻한 색감의 점심 할인 포스터', CONCAT('world-tpl-', t, '-', v),
       html, '요청하신 분위기로 템플릿을 만들었어요.', created_at, COALESCE(deleted_at, created_at), deleted_at
FROM base_tpl_version
ON DUPLICATE KEY UPDATE template_version_id = template_version.template_version_id;

-- template_quota 는 스키마 마이그레이션이 넣은 1행을 템플릿 행 수로 맞춘다 (행을 더 넣지 않는다)
UPDATE template_quota
SET current_count = (SELECT COUNT(*) FROM template),
    updated_at = TIMESTAMP(DATE('${baseDate}'), '00:00:00');

/*
 * 캠페인 값을 먼저 임시 표에 계산하고 campaign, campaign_status_log, poster 에 나눠 넣는다.
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
 * CMP.ACTIVE, CMP.ACTIVE_MANY, CMP.ACTIVE_MENU: 1 ~ 180 은 D-14 ~ D+14.
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
WHERE n <= 180 OR n = 242;

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

-- 쿠폰 조건, 대상, 예산 공식 (WORLD_SPEC 캠페인 (V4) 아래 목록)
UPDATE base_cmp
SET target_type = CASE WHEN s % 4 > 0 AND n % 2 = 0 THEN 'MENU' ELSE 'ALL' END,
    discount_type = CASE WHEN s % 4 > 0 AND n % 2 = 0 AND n % 4 <> 3 THEN 'PERCENT' ELSE 'AMOUNT' END,
    discount_value = CASE WHEN s % 4 > 0 AND n % 2 = 0 THEN IF(n % 4 = 3, 2000, 10 + n % 4 * 5)
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
 * PST.PASSED, PST.SAVED, PST.SAVED_FAILING: DRAFT 단계 0 ~ 2 를 빼고 모두 포스터가 있다.
 * 템플릿 B + (n % 8) + 1 의 HTML 슬롯에 가게 이름, 할인 문구, 사용 시간, 이미지 주소를 채운다.
 * DRAFT 단계 3 중 241 은 검수 전 (PST.SAVED), 나머지는 금지 표현이 들어가 검수 FAIL (PST.SAVED_FAILING) 이다.
 */
INSERT INTO poster (poster_id, campaign_id, template_id, title, slot_values, html_content, created_at, updated_at,
                    image_object_key)
SELECT src.id, src.id, src.template_id, LEFT(CONCAT(src.store_name, ' - ', src.template_name), 100),
       JSON_OBJECT('eventName', JSON_OBJECT('type', 'text', 'value', src.store_name),
                   'discount', JSON_OBJECT('type', 'text', 'value', src.discount_text),
                   'period', JSON_OBJECT('type', 'text', 'value', src.period_text),
                   'image', JSON_OBJECT('type', 'image', 'value', src.image_url)),
       REPLACE(REPLACE(REPLACE(REPLACE(src.template_html,
           '>이벤트명<', CONCAT('>', src.store_name, '<')),
           '>할인 내용<', CONCAT('>', src.discount_text, '<')),
           '>기간<', CONCAT('>', src.period_text, '<')),
           'alt="메뉴 이미지">',
           CONCAT('alt="메뉴 이미지" src="', src.image_url, '" style="width:100%;height:100%;min-height:0;object-fit:cover">')),
       src.created_at, src.created_at,
       CONCAT('world/campaigns/', src.id, '/poster.jpg')
FROM (
    SELECT 10000000 + c.n AS id,
           tp.template_id,
           tp.name AS template_name,
           tp.html_content AS template_html,
           c.store_name,
           CONCAT(IF(c.target_type = 'ALL', '전체 메뉴', c.menu_name), ' ',
                  IF(c.discount_type = 'PERCENT', CONCAT(c.discount_value, '%'), CONCAT(c.discount_value, '원')),
                  ' 할인', IF(c.stage = 3 AND c.n <> 241, ' 최저가 보장', '')) AS discount_text,
           CONCAT('매일 ', TIME_FORMAT(c.usable_start, '%H:%i'), ' ~ ', TIME_FORMAT(c.usable_end, '%H:%i')) AS period_text,
           CONCAT('${baseCdnBaseUrl}/world/campaigns/', 10000000 + c.n, '/poster.jpg') AS image_url,
           c.created_at + INTERVAL 30 MINUTE AS created_at
    FROM base_cmp c
    JOIN template tp ON tp.template_id = 10000000 + c.n % 8 + 1
    WHERE c.stage IS NULL OR c.stage = 3
) src
ON DUPLICATE KEY UPDATE poster_id = poster.poster_id;

-- PST.PASSED: DRAFT 를 뺀 포스터는 검수 PASS, PST.SAVED_FAILING: DRAFT 단계 3 (241 제외) 은 금지 표현으로 FAIL
INSERT INTO poster_moderation_result (poster_moderation_result_id, poster_id, result, reason, created_at, updated_at)
SELECT 10000000 + n, 10000000 + n,
       IF(stage = 3, 'FAIL', 'PASS'),
       IF(stage = 3, 'FORBIDDEN_EXPRESSION', NULL),
       created_at + INTERVAL 40 MINUTE, created_at + INTERVAL 40 MINUTE
FROM base_cmp
WHERE stage IS NULL OR (stage = 3 AND n <> 241)
ON DUPLICATE KEY UPDATE poster_moderation_result_id = poster_moderation_result.poster_moderation_result_id;

/*
 * CMP.ACTIVE, CMP.ACTIVE_MENU, STO.NO_CAMPAIGN: 서빙 대상 캠페인 (ACTIVE 1 ~ 180, 242) 의 D-7 ~ D+1 날짜 중 기간 안인 날마다 요약 1행.
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
DROP TEMPORARY TABLE base_tpl_version;
DROP TEMPORARY TABLE base_n;
