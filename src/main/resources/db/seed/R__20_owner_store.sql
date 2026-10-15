/*
 * 점주 둘에게 가게를 하나씩 둔다.
 * 가게는 등록을 마친 상태(finalized)다.
 * 위치는 강남역과 역삼역 사이다.
 * 시드 회원(R__40)과 1 km 안에 있어 피드 반경에 든다.
 */
INSERT INTO owner (owner_id, email, password_hash, role, status, tutorial_viewed, created_at, updated_at)
VALUES (9001, 'owner1@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9002, 'owner2@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE owner_id = owner_id;

INSERT INTO store (store_id, owner_id, name, category_code, representative_name, phone, road_address,
                   latitude, longitude, business_registration_number, business_verification_status,
                   business_verified_at, finalized, finalized_at, created_at, updated_at)
VALUES (9001, 9001, '런치 한식당', 'KOREAN', '김점주', '02-0000-9001', '서울 강남구 강남대로 396',
        37.4979000, 127.0276000, '9000000001', 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6)),
       (9002, 9002, '런치 분식', 'BUNSIK', '이점주', '02-0000-9002', '서울 강남구 테헤란로 156',
        37.5006000, 127.0364000, '9000000002', 'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE store_id = store_id;

INSERT INTO store_menu (store_menu_id, store_id, name, price, description, sort_order, created_at, updated_at)
VALUES (9001, 9001, '제육볶음 정식', 9000, '매콤한 제육볶음과 밥, 국', 1, NOW(6), NOW(6)),
       (9002, 9001, '된장찌개 정식', 8000, '집된장으로 끓인 찌개', 2, NOW(6), NOW(6)),
       (9003, 9001, '김치찌개 정식', 8000, '묵은지 김치찌개', 3, NOW(6), NOW(6)),
       (9004, 9002, '떡볶이', 6000, '쌀떡볶이 1인분', 1, NOW(6), NOW(6)),
       (9005, 9002, '김밥', 4000, '야채 김밥 한 줄', 2, NOW(6), NOW(6)),
       (9006, 9002, '라볶이', 7000, '라면 사리 추가', 3, NOW(6), NOW(6))
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
