/*
 * 점주마다 가게를 하나씩 둔다. 점주 계정 하나에 가게 하나다(uk_store_owner).
 * 가게는 등록을 마친 상태(finalized)다.
 * 9001, 9002 는 강남역과 역삼역 사이에 있어 시드 회원(R__40)과 1 km 안이다.
 * 9003~9016 은 피드 규칙을 시험하려고 강남역(37.4979, 127.0276) 둘레에 흩어 둔 가게다. 배치는 R__50 의 후보 주석에 있다.
 */
INSERT INTO owner (owner_id, email, password_hash, role, status, tutorial_viewed, created_at, updated_at)
VALUES (9001, 'owner1@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9002, 'owner2@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9003, 'owner3@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9004, 'owner4@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9005, 'owner5@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9006, 'owner6@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9007, 'owner7@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9008, 'owner8@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9009, 'owner9@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9010, 'owner10@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9011, 'owner11@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9012, 'owner12@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9013, 'owner13@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9014, 'owner14@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9015, 'owner15@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9016, 'owner16@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE owner_id = owner_id;

INSERT INTO store (store_id, owner_id, name, category_code, representative_name, phone, road_address,
                   latitude, longitude, business_registration_number, business_verification_status,
                   business_verified_at, finalized, finalized_at, created_at, updated_at)
VALUES (9001, 9001, '런치 한식당', 'KOREAN', '김점주', '02-0000-9001', '서울 강남구 강남대로 396',
        37.4979000, 127.0276000, '9000000001', 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6)),
       (9002, 9002, '런치 분식', 'BUNSIK', '이점주', '02-0000-9002', '서울 강남구 테헤란로 156',
        37.5006000, 127.0364000, '9000000002', 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6)),
       (9003, 9003, '런치 스시', 'JAPANESE', '점주3', '02-0000-9003', '서울 강남구 시드로 3',
        37.4999000, 127.0276000, '9000000003', 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6)),
       (9004, 9004, '런치 짜장', 'CHINESE', '점주4', '02-0000-9004', '서울 강남구 시드로 4',
        37.4954000, 127.0276000, '9000000004', 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6)),
       (9005, 9005, '런치 파스타', 'WESTERN', '점주5', '02-0000-9005', '서울 강남구 시드로 5',
        37.4979000, 127.0316000, '9000000005', 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6)),
       (9006, 9006, '런치 카페', 'CAFE_DESSERT', '점주6', '02-0000-9006', '서울 강남구 시드로 6',
        37.4979000, 127.0231000, '9000000006', 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6)),
       (9007, 9007, '런치 쌀국수', 'ASIAN', '점주7', '02-0000-9007', '서울 강남구 시드로 7',
        37.5019000, 127.0276000, '9000000007', 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6)),
       (9008, 9008, '런치 버거', 'FAST_FOOD', '점주8', '02-0000-9008', '서울 강남구 시드로 8',
        37.4929000, 127.0276000, '9000000008', 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6)),
       (9009, 9009, '런치 국밥', 'KOREAN', '점주9', '02-0000-9009', '서울 강남구 시드로 9',
        37.5089000, 127.0276000, '9000000009', 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6)),
       (9010, 9010, '런치 라멘', 'JAPANESE', '점주10', '02-0000-9010', '서울 강남구 시드로 10',
        37.4849000, 127.0276000, '9000000010', 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6)),
       (9011, 9011, '런치 마라탕', 'CHINESE', '점주11', '02-0000-9011', '서울 강남구 시드로 11',
        37.4979000, 127.0456000, '9000000011', 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6)),
       (9012, 9012, '런치 스테이크', 'WESTERN', '점주12', '02-0000-9012', '서울 강남구 시드로 12',
        37.4979000, 127.0126000, '9000000012', 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6)),
       (9013, 9013, '런치 백반', 'KOREAN', '점주13', '02-0000-9013', '서울 강남구 시드로 13',
        37.5039000, 127.0276000, '9000000013', 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6)),
       (9014, 9014, '런치 순대', 'BUNSIK', '점주14', '02-0000-9014', '서울 강남구 시드로 14',
        37.4969000, 127.0276000, '9000000014', 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6)),
       (9015, 9015, '런치 디저트', 'CAFE_DESSERT', '점주15', '02-0000-9015', '서울 강남구 시드로 15',
        37.4979000, 127.0291000, '9000000015', 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6)),
       (9016, 9016, '런치 도시락', 'OTHER', '점주16', '02-0000-9016', '서울 강남구 시드로 16',
        37.4989000, 127.0276000, '9000000016', 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE store_id = store_id;

INSERT INTO store_menu (store_menu_id, store_id, name, price, description, sort_order, created_at, updated_at)
VALUES (9001, 9001, '제육볶음 정식', 9000, '매콤한 제육볶음과 밥, 국', 1, NOW(6), NOW(6)),
       (9002, 9001, '된장찌개 정식', 8000, '집된장으로 끓인 찌개', 2, NOW(6), NOW(6)),
       (9003, 9001, '김치찌개 정식', 8000, '묵은지 김치찌개', 3, NOW(6), NOW(6)),
       (9004, 9002, '떡볶이', 6000, '쌀떡볶이 1인분', 1, NOW(6), NOW(6)),
       (9005, 9002, '김밥', 4000, '야채 김밥 한 줄', 2, NOW(6), NOW(6)),
       (9006, 9002, '라볶이', 7000, '라면 사리 추가', 3, NOW(6), NOW(6)),
       (9110, 9010, '돈코츠 라멘', 11000, '시드 메뉴', 1, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE store_menu_id = store_menu_id;

-- 평일 10:00~21:00, 토요일 11:00~20:00, 일요일 휴무
INSERT INTO store_business_hour (store_business_hour_id, store_id, day_of_week, open_time, close_time, closed,
                                 break_start_time, break_end_time, last_order_time, created_at, updated_at)
VALUES (9001, 9001, 'MONDAY', '10:00', '21:00', 0, '15:00', '16:00', '20:30', NOW(6), NOW(6)),
       (9002, 9001, 'TUESDAY', '10:00', '21:00', 0, '15:00', '16:00', '20:30', NOW(6), NOW(6)),
       (9003, 9001, 'WEDNESDAY', '10:00', '21:00', 0, '15:00', '16:00', '20:30', NOW(6), NOW(6)),
       (9004, 9001, 'THURSDAY', '10:00', '21:00', 0, '15:00', '16:00', '20:30', NOW(6), NOW(6)),
       (9005, 9001, 'FRIDAY', '10:00', '21:00', 0, '15:00', '16:00', '20:30', NOW(6), NOW(6)),
       (9006, 9001, 'SATURDAY', '11:00', '20:00', 0, NULL, NULL, '19:30', NOW(6), NOW(6)),
       (9007, 9001, 'SUNDAY', NULL, NULL, 1, NULL, NULL, NULL, NOW(6), NOW(6)),
       (9011, 9002, 'MONDAY', '10:00', '21:00', 0, NULL, NULL, '20:30', NOW(6), NOW(6)),
       (9012, 9002, 'TUESDAY', '10:00', '21:00', 0, NULL, NULL, '20:30', NOW(6), NOW(6)),
       (9013, 9002, 'WEDNESDAY', '10:00', '21:00', 0, NULL, NULL, '20:30', NOW(6), NOW(6)),
       (9014, 9002, 'THURSDAY', '10:00', '21:00', 0, NULL, NULL, '20:30', NOW(6), NOW(6)),
       (9015, 9002, 'FRIDAY', '10:00', '21:00', 0, NULL, NULL, '20:30', NOW(6), NOW(6)),
       (9016, 9002, 'SATURDAY', '11:00', '20:00', 0, NULL, NULL, '19:30', NOW(6), NOW(6)),
       (9017, 9002, 'SUNDAY', NULL, NULL, 1, NULL, NULL, NULL, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE store_business_hour_id = store_business_hour_id;

/*
 * 피드 시험용 점주와 가게 9101~9200 이다(#611). 점주, 가게, 캠페인, 포스터 번호가 같다.
 * 9101~9110 은 피드에서 빠지는 경우다. 9101, 9102 는 노출 반경 500 보다 멀고 9103, 9104 는 노출 반경 1000 보다 멀다. 9105~9110 은 1 km 안이지만 예산 계획이 없거나(9105~9107) 일시 중단(9108~9110)이다.
 * 9111~9155 는 강남역에서 1.1~1.9 km, 9156~9200 은 2.1~2.9 km 에 둔다. 방향은 번호마다 37도씩 돈다.
 * 업종은 노출 대상 성별(R__30)마다 따로 돌린다. 전체 대상 캠페인에 업종을 고르게 줘야 기타 성별 회원도 업종마다 5개 이상 받는다.
 */
INSERT INTO owner (owner_id, email, password_hash, role, status, tutorial_viewed, created_at, updated_at)
WITH RECURSIVE seq (id) AS (SELECT 9101 UNION ALL SELECT id + 1 FROM seq WHERE id < 9200)
SELECT id, CONCAT('owner', id - 9000, '@seed.lunchcatch.com'), '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)
FROM seq
ON DUPLICATE KEY UPDATE owner_id = owner_id;

INSERT INTO store (store_id, owner_id, name, category_code, representative_name, phone, road_address,
                   latitude, longitude, business_registration_number, business_verification_status,
                   business_verified_at, finalized, finalized_at, created_at, updated_at)
WITH RECURSIVE seq (id) AS (SELECT 9101 UNION ALL SELECT id + 1 FROM seq WHERE id < 9200),
               n AS (SELECT id, id - 9101 AS k, id - 9111 AS j FROM seq),
               p AS (SELECT id,
                            CASE WHEN k < 10 THEN MOD(k, 9)
                                 WHEN MOD(j, 9) < 5 THEN MOD(FLOOR(j / 9) * 5 + MOD(j, 9), 9)
                                 ELSE MOD(FLOOR(j / 9) * 4 + MOD(j, 9) - 5, 9) END AS cat,
                            CASE WHEN k < 2 THEN 600 + k * 200
                                 WHEN k < 4 THEN 1200 + (k - 2) * 300
                                 WHEN k < 10 THEN 300 + (k - 4) * 100
                                 WHEN j < 45 THEN 1100 + MOD(j, 5) * 200
                                 ELSE 2100 + MOD(j, 5) * 200 END AS dist,
                            IF(k < 10, k * 40, MOD(j * 37, 360)) AS deg
                     FROM n)
SELECT id, id,
       CONCAT('강남 ', ELT(cat + 1, '도시락', '분식', '쌀국수', '버거', '디저트', '한식', '중식', '일식', '양식'), ' ', id - 9100, '호점'),
       ELT(cat + 1, 'OTHER', 'BUNSIK', 'ASIAN', 'FAST_FOOD', 'CAFE_DESSERT', 'KOREAN', 'CHINESE', 'JAPANESE', 'WESTERN'),
       CONCAT('점주', id - 9000), CONCAT('02-0000-', id), CONCAT('서울 강남구 시드로 ', id - 9000),
       ROUND(37.4979 + dist * COS(RADIANS(deg)) / 111320, 7),
       ROUND(127.0276 + dist * SIN(RADIANS(deg)) / (111320 * COS(RADIANS(37.4979))), 7),
       CONCAT('900000', id), 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6)
FROM p
ON DUPLICATE KEY UPDATE store_id = store_id;

-- 가게마다 메뉴 하나다. 메뉴 번호는 가게 번호 + 200 이고 메뉴 할인 캠페인이 이 메뉴를 고른다
INSERT INTO store_menu (store_menu_id, store_id, name, price, description, sort_order, created_at, updated_at)
SELECT s.store_id + 200, s.store_id,
       CASE s.category_code WHEN 'KOREAN' THEN '제육볶음 정식' WHEN 'CHINESE' THEN '짬뽕' WHEN 'JAPANESE' THEN '연어덮밥'
                            WHEN 'WESTERN' THEN '크림 파스타' WHEN 'BUNSIK' THEN '떡볶이 세트' WHEN 'ASIAN' THEN '양지 쌀국수'
                            WHEN 'FAST_FOOD' THEN '치즈버거 세트' WHEN 'CAFE_DESSERT' THEN '샌드위치 세트' ELSE '불고기 도시락' END,
       8000 + MOD(s.store_id, 5) * 1000, '시드 메뉴', 1, NOW(6), NOW(6)
FROM store s
WHERE s.store_id BETWEEN 9101 AND 9200
ON DUPLICATE KEY UPDATE store_menu_id = store_menu_id;

-- 매일 10:00~22:00 이다. 번호는 가게 번호 x 10 + 요일 순서다
INSERT INTO store_business_hour (store_business_hour_id, store_id, day_of_week, open_time, close_time, closed,
                                 break_start_time, break_end_time, last_order_time, created_at, updated_at)
WITH RECURSIVE dow (d) AS (SELECT 1 UNION ALL SELECT d + 1 FROM dow WHERE d < 7)
SELECT s.store_id * 10 + dow.d, s.store_id,
       ELT(dow.d, 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'),
       '10:00', '22:00', 0, NULL, NULL, '21:30', NOW(6), NOW(6)
FROM store s
CROSS JOIN dow
WHERE s.store_id BETWEEN 9101 AND 9200
ON DUPLICATE KEY UPDATE store_business_hour_id = store_business_hour_id;
