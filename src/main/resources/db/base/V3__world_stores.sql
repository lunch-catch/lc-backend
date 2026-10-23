/*
 * 기본 세계의 가게와 가게 등록 진행 상태를 넣는다 (#741, WORLD_SPEC "가게 (V3)" 와 "지역").
 * store_id = B + n 이고 B = 10000000 이며 점주 n 의 가게다.
 * 점주 1 ~ 250, 291 ~ 500 은 확정 가게, 점주 251 ~ 290 은 진행 단계 LOCATION 이상이면 미확정 가게다.
 * 다른 V 파일의 행을 읽지 않는다. store 와 store_registration_progress 는 owner 에 외래 키가 없어 이 파일만 돌려도 들어간다.
 */

-- 숫자 표 1 ~ 500 은 숫자 열 교차 조인으로 만든다
CREATE TEMPORARY TABLE base_n (n INT PRIMARY KEY);
INSERT INTO base_n (n)
SELECT h.d * 100 + t.d * 10 + o.d + 1
FROM (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) h
CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) t
CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) o;

-- 메뉴와 사진 자리 번호다 (0 은 로고)
CREATE TEMPORARY TABLE base_slot (i INT PRIMARY KEY);
INSERT INTO base_slot (i) VALUES (0), (1), (2), (3);

-- 요일 번호 1 ~ 7 은 MONDAY ~ SUNDAY 다
CREATE TEMPORARY TABLE base_dow (d INT PRIMARY KEY, day_name VARCHAR(10) NOT NULL);
INSERT INTO base_dow (d, day_name) VALUES
    (1, 'MONDAY'), (2, 'TUESDAY'), (3, 'WEDNESDAY'), (4, 'THURSDAY'), (5, 'FRIDAY'), (6, 'SATURDAY'), (7, 'SUNDAY');

/*
 * 가게 n 의 위치다 (#858). V3 의 가게와 V6 의 회원 위치가 같은 역 좌표를 쓴다.
 * 역은 0 강남역, 1 역삼역, 2 선릉역이다. 진행 중 캠페인 가게(1 ~ 180, 301 ~ 330)는 순서대로 역마다 70곳, 그 밖의 확정 가게 90곳(181 ~ 250, 291 ~ 300, 331 ~ 340)은 역마다 30곳이다.
 * 역 둘레 가게는 역에서 420 m 안이다. 강남역과 역삼역이 850 m 떨어져 있어 그 절반보다 가깝게 두어야 가장 가까운 역이 제 역이 된다.
 * 나머지 200곳(251 ~ 290, 341 ~ 500)은 강남구의 신사역, 압구정역, 학동역, 삼성역, 대치역, 개포동역, 수서역 둘레 800 m 안이다.
 */
CREATE TEMPORARY TABLE base_geo AS
SELECT g.n, g.st,
       CASE WHEN g.st IS NULL THEN ELT(g.k % 7 + 1, 37.5163, 37.5271, 37.5143, 37.5089, 37.4946, 37.4892, 37.4873)
            ELSE ELT(g.st + 1, 37.4979, 37.5006, 37.5045) END AS clat,
       CASE WHEN g.st IS NULL THEN ELT(g.k % 7 + 1, 127.0203, 127.0284, 127.0317, 127.0631, 127.0635, 127.0662, 127.1017)
            ELSE ELT(g.st + 1, 127.0276, 127.0364, 127.0490) END AS clng,
       CASE WHEN g.st IS NULL THEN 100 + (g.k * 53) % 700 ELSE 60 + (g.j * 37) % 360 END AS r,
       CASE WHEN g.st IS NULL THEN (g.k * 137) % 360 ELSE (g.j * 137 + g.st * 40) % 360 END AS a
FROM (
    SELECT x.n,
           x.i % 3 AS st,
           CASE WHEN x.grp = 'A' THEN x.i DIV 3 ELSE 70 + x.i DIV 3 END AS j,
           x.k
    FROM (
        SELECT n,
               CASE WHEN n <= 180 OR n BETWEEN 301 AND 330 THEN 'A'
                    WHEN n BETWEEN 181 AND 250 OR n BETWEEN 291 AND 300 OR n BETWEEN 331 AND 340 THEN 'T' END AS grp,
               CASE WHEN n <= 180 THEN n - 1
                    WHEN n BETWEEN 301 AND 330 THEN n - 121
                    WHEN n BETWEEN 181 AND 250 THEN n - 181
                    WHEN n BETWEEN 291 AND 300 THEN n - 221
                    WHEN n BETWEEN 331 AND 340 THEN n - 251 END AS i,
               CASE WHEN n BETWEEN 251 AND 290 THEN n - 250 WHEN n >= 341 THEN n - 300 END AS k
        FROM base_n
        WHERE n <= 500
    ) x
) g;

/*
 * 가게마다 계산값을 한 번 만든다.
 * step 은 확정 가게면 9, 진행 중이면 (n - 251) % 7 (2 LOCATION, 3 HOURS, 4 IMAGES, 5 MENU, 6 MENU 이면서 확정 조건 충족) 이다.
 * 좌표는 base_geo 의 역 둘레 공식이다.
 */
CREATE TEMPORARY TABLE base_store AS
SELECT
    g.n,
    10000000 + g.n AS store_id,
    10000000 + g.n AS owner_id,
    g.step,
    ELT(g.n % 9 + 1, 'KOREAN', 'CHINESE', 'JAPANESE', 'WESTERN', 'BUNSIK', 'ASIAN', 'FAST_FOOD', 'CAFE_DESSERT', 'OTHER') AS cat,
    ELT(g.n % 9 + 1, '한식', '중식', '일식', '양식', '분식', '아시안', '패스트푸드', '카페디저트', '기타') AS cat_kr,
    ROUND(g.clat + g.r * COS(RADIANS(g.a)) / 111000, 7) AS lat,
    ROUND(g.clng + g.r * SIN(RADIANS(g.a)) / 88200, 7) AS lng,
    TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL (120 - g.n % 110) DAY), '11:00:00') AS created_at,
    TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 1 DAY), '10:00:00') AS draft_at
FROM (
    SELECT
        base_n.n,
        CASE WHEN base_n.n BETWEEN 251 AND 290 THEN (base_n.n - 251) % 7 ELSE 9 END AS step,
        geo.a,
        geo.r,
        geo.clat,
        geo.clng
    FROM base_n
    JOIN base_geo geo ON geo.n = base_n.n
) g
WHERE g.step >= 2;

/*
 * STO.FINALIZED, STO.MANY_NEARBY, STO.NO_CAMPAIGN(243 ~ 250), STO.NO_PHONE(245, phone NULL), STO.NOT_FINALIZED(LOCATION 단계).
 * OWN.ONBOARDING_LOCATION 가게는 위치가 비어 있고 OWN.ONBOARDING_HOURS 부터 위치가 있다.
 * OWN.MANY_MIXED 의 상호 부분 일치용으로 LOCATION 단계 가게 6곳 이름에 '김밥' 을 넣는다.
 */
INSERT INTO store (store_id, owner_id, name, category_code, representative_name, phone, road_address,
                   latitude, longitude, kakao_place_id, business_registration_number,
                   business_verification_status, business_verified_at, finalized, finalized_at, created_at, updated_at)
SELECT
    s.store_id,
    s.owner_id,
    CASE WHEN s.step = 2 THEN CONCAT(s.cat_kr, ' 김밥 ', LPAD(s.n, 3, '0'), '호점')
         ELSE CONCAT(s.cat_kr, ' ', LPAD(s.n, 3, '0'), '호점') END,
    s.cat,
    CONCAT('시나리오대표', LPAD(s.n, 3, '0')),
    CASE WHEN s.n = 245 THEN NULL ELSE CONCAT('02-555-', LPAD(s.n, 4, '0')) END,
    CASE WHEN s.step >= 3 THEN CONCAT('서울 강남구 시나리오로 ', s.n) END,
    CASE WHEN s.step >= 3 THEN s.lat END,
    CASE WHEN s.step >= 3 THEN s.lng END,
    CASE WHEN s.step >= 3 THEN CONCAT('world-', s.n) END,
    CONCAT('9', LPAD(s.n, 9, '0')),
    'VERIFIED',
    s.created_at,
    s.step = 9,
    CASE WHEN s.step = 9 THEN s.created_at + INTERVAL 1 HOUR END,
    s.created_at,
    CASE WHEN s.step = 9 THEN s.created_at + INTERVAL 1 HOUR ELSE s.draft_at END
FROM base_store s
ON DUPLICATE KEY UPDATE store.store_id = store.store_id;

/*
 * 영업시간은 n % 10 공식이다. 0 은 수요일만 휴무이고 나머지 요일은 기본 시간(10:30 ~ 21:30, 브레이크 15:00 ~ 16:30)이다.
 * STO.CLOSED_TODAY 는 n % 10 = 0 (baseDate 수요일) 과 기본 규칙 가게의 일요일이다.
 * STO.FINALIZED_FULL(247) 은 월 화 수 금 11:00 ~ 20:00 이고 목 토 일 휴무다.
 * STO.FINALIZED_BARE(248) 는 영업시간이 없다. 진행 중 가게는 OWN.ONBOARDING_IMAGES 단계부터 7행이다.
 */
INSERT INTO store_business_hour (store_business_hour_id, store_id, day_of_week, open_time, close_time, closed,
                                 break_start_time, break_end_time, last_order_time, created_at, updated_at)
SELECT
    h.store_business_hour_id, h.store_id, h.day_name,
    CASE WHEN h.closed THEN NULL WHEN h.rule_code = 'F' THEN '11:00' WHEN h.rule_code = '1' THEN '13:00'
         WHEN h.rule_code = '2' THEN '10:00' ELSE '10:30' END,
    CASE WHEN h.closed THEN NULL WHEN h.rule_code = 'F' THEN '20:00' WHEN h.rule_code = '1' THEN '21:00'
         WHEN h.rule_code = '2' THEN '22:00' ELSE '21:30' END,
    h.closed,
    CASE WHEN h.closed OR h.rule_code IN ('F', '1', '2') THEN NULL ELSE '15:00' END,
    CASE WHEN h.closed OR h.rule_code IN ('F', '1', '2') THEN NULL ELSE '16:30' END,
    CASE WHEN h.closed THEN NULL WHEN h.rule_code = 'F' THEN '19:30' WHEN h.rule_code = '1' THEN '20:30'
         WHEN h.rule_code = '2' THEN '21:30' ELSE '21:00' END,
    h.at, h.at
FROM (
    SELECT
        10000000 + s.n * 10 + w.d AS store_business_hour_id,
        s.store_id,
        w.day_name,
        CASE WHEN s.n = 247 THEN 'F' WHEN s.n % 10 = 1 THEN '1' WHEN s.n % 10 = 2 THEN '2' ELSE 'D' END AS rule_code,
        CASE
            WHEN s.n = 247 THEN w.d IN (4, 6, 7)
            WHEN s.n % 10 = 0 THEN w.d = 3
            WHEN s.n % 10 IN (1, 2) THEN FALSE
            ELSE w.d = 7
        END AS closed,
        CASE WHEN s.step = 9 THEN s.created_at ELSE s.draft_at END AS at
    FROM base_store s
    CROSS JOIN base_dow w
    WHERE (s.step = 9 AND s.n <> 248) OR s.step IN (4, 5, 6)
) h
ON DUPLICATE KEY UPDATE store_business_hour.store_business_hour_id = store_business_hour.store_business_hour_id;

/*
 * 로고(i = 0)와 매장 사진(i = 1 ~ 3). 확정 가게와 메뉴 단계 가게는 로고 1장과 매장 사진 n % 4 장이다.
 * STO.NO_IMAGES(244) 와 STO.FINALIZED_BARE(248) 는 로고가 없다 (둘 다 n % 4 = 0 이라 매장 사진과 메뉴도 없다).
 * OWN.ONBOARDING_IMAGES 단계 가게 255, 283, 290 은 사진이 없다.
 * STO.DRAFT_IMAGES_DONE(262) 는 로고와 매장 사진 1, 2 가 VERIFIED 다.
 * STO.DRAFT_INTERIOR_3_PENDING(269) 는 매장 사진 1, 2 VERIFIED 에 3 이 PENDING 이다.
 * STO.DRAFT_LOGO_PENDING(276) 은 로고 한 장이 PENDING 이다.
 */
INSERT INTO store_image (store_image_id, store_id, upload_id, image_type, object_key, original_filename, content_type,
                         file_size, width, height, sort_order, upload_status, verified_at, created_at, updated_at)
SELECT
    m.store_image_id, m.store_id,
    CONCAT('0000000a-0000-4000-8000-', LOWER(LPAD(HEX(m.store_image_id), 12, '0'))),
    CASE WHEN m.i = 0 THEN 'LOGO' ELSE 'INTERIOR' END,
    CASE
        WHEN m.i = 0 AND m.n = 276 THEN CONCAT('world/stores/', m.store_id, '/logo-pending.jpg')
        WHEN m.i = 0 THEN CONCAT('world/stores/', m.store_id, '/logo.jpg')
        ELSE CONCAT('world/stores/', m.store_id, '/interior-', m.i, '.jpg')
    END,
    CASE WHEN m.i = 0 THEN 'logo.jpg' ELSE CONCAT('interior-', m.i, '.jpg') END,
    'image/jpeg',
    200000 + m.n * 100 + m.i,
    800, 800,
    CASE WHEN m.i = 0 THEN NULL ELSE m.i END,
    m.upload_status,
    CASE WHEN m.upload_status = 'VERIFIED' THEN m.at END,
    m.at, m.at
FROM (
    SELECT
        10000000 + s.n * 10 + k.i AS store_image_id,
        s.store_id, s.n, k.i,
        CASE WHEN (s.n = 276 AND k.i = 0) OR (s.n = 269 AND k.i = 3) THEN 'PENDING' ELSE 'VERIFIED' END AS upload_status,
        CASE WHEN s.step = 9 THEN s.created_at ELSE s.draft_at END AS at
    FROM base_store s
    CROSS JOIN base_slot k
    WHERE CASE
        WHEN k.i = 0 THEN (s.step = 9 AND s.n NOT IN (244, 248)) OR s.step IN (5, 6) OR s.n IN (262, 276)
        ELSE (s.step IN (9, 5, 6) AND k.i <= s.n % 4) OR (s.n = 262 AND k.i <= 2) OR s.n = 269
    END
) m
ON DUPLICATE KEY UPDATE store_image.store_image_id = store_image.store_image_id;

/*
 * 메뉴는 확정 가게면 n % 4 개다. 가격은 8000 + (n * 500 + i * 1000) % 7000 이다.
 * OWN.ONBOARDING_MENU 단계(5) 가게는 메뉴가 없고 STO.DRAFT_MENU_NO_IMAGE(263) 만 사진 없는 메뉴 1개를 둔다.
 * STO.DRAFT_MENU_3 은 확정 조건을 채운 메뉴 단계(6) 가게 5곳이며 메뉴 3개와 사진이 모두 VERIFIED 다.
 */
INSERT INTO store_menu (store_menu_id, store_id, name, price, description, sort_order, created_at, updated_at)
SELECT
    10000000 + s.n * 10 + k.i,
    s.store_id,
    CONCAT(s.cat_kr, ' 메뉴 ', k.i),
    8000 + (s.n * 500 + k.i * 1000) % 7000,
    CONCAT(s.cat_kr, ' ', LPAD(s.n, 3, '0'), '호점 대표 메뉴'),
    k.i,
    CASE WHEN s.step = 9 THEN s.created_at ELSE s.draft_at END,
    CASE WHEN s.step = 9 THEN s.created_at ELSE s.draft_at END
FROM base_store s
CROSS JOIN base_slot k
WHERE k.i >= 1
  AND ((s.step = 9 AND k.i <= s.n % 4) OR s.step = 6 OR (s.n = 263 AND k.i = 1))
ON DUPLICATE KEY UPDATE store_menu.store_menu_id = store_menu.store_menu_id;

-- 메뉴마다 사진 1장 VERIFIED 다 (STO.DRAFT_MENU_NO_IMAGE 의 263 은 빼고)
INSERT INTO store_menu_image (store_menu_image_id, store_menu_id, upload_id, object_key, original_filename, content_type,
                              file_size, upload_status, verified_at, created_at, updated_at)
SELECT
    10000000 + s.n * 10 + k.i,
    10000000 + s.n * 10 + k.i,
    CONCAT('0000000b-0000-4000-8000-', LOWER(LPAD(HEX(10000000 + s.n * 10 + k.i), 12, '0'))),
    CONCAT('world/stores/', s.store_id, '/menu-', k.i, '.jpg'),
    CONCAT('menu-', k.i, '.jpg'),
    'image/jpeg',
    150000 + s.n * 100 + k.i,
    'VERIFIED',
    CASE WHEN s.step = 9 THEN s.created_at ELSE s.draft_at END,
    CASE WHEN s.step = 9 THEN s.created_at ELSE s.draft_at END,
    CASE WHEN s.step = 9 THEN s.created_at ELSE s.draft_at END
FROM base_store s
CROSS JOIN base_slot k
WHERE k.i >= 1
  AND ((s.step = 9 AND k.i <= s.n % 4) OR s.step = 6)
ON DUPLICATE KEY UPDATE store_menu_image.store_menu_image_id = store_menu_image.store_menu_image_id;

/*
 * 점주 251 ~ 290 의 가게 등록 진행 상태다. 단계는 (n - 251) % 7 이다.
 * OWN.ONBOARDING_TERMS(0) 은 행이 없다 (약관 저장 때 앱이 만든다).
 * OWN.ONBOARDING_STORE_INFO(1) 은 store_id NULL 이고 OWN.ONBOARDING_LOCATION, OWN.ONBOARDING_HOURS, OWN.ONBOARDING_IMAGES, OWN.ONBOARDING_MENU 는 위의 미확정 가게를 가리킨다.
 * 버전은 단계마다 1 ~ 5 이고 메뉴 3개를 저장한 6 은 8 이다.
 */
INSERT INTO store_registration_progress (store_registration_progress_id, owner_id, store_id, resume_step, version,
                                         created_at, updated_at)
SELECT
    10000000 + p.n,
    10000000 + p.n,
    CASE WHEN p.s >= 2 THEN 10000000 + p.n END,
    ELT(p.s, 'STORE_INFO', 'LOCATION', 'HOURS', 'IMAGES', 'MENU', 'MENU'),
    ELT(p.s, 1, 2, 3, 4, 5, 8),
    TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL (120 - p.n % 110) DAY), '10:30:00'),
    TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 1 DAY), '10:00:00')
FROM (SELECT n, (n - 251) % 7 AS s FROM base_n WHERE n BETWEEN 251 AND 290) p
WHERE p.s >= 1
ON DUPLICATE KEY UPDATE store_registration_progress.store_registration_progress_id = store_registration_progress.store_registration_progress_id;

DROP TEMPORARY TABLE base_store;
DROP TEMPORARY TABLE base_geo;
DROP TEMPORARY TABLE base_dow;
DROP TEMPORARY TABLE base_slot;
DROP TEMPORARY TABLE base_n;
