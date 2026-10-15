/*
 * 온보딩을 마친 시험 회원 둘이다.
 * 로그인은 카카오로만 되므로 이 회원으로 로그인하지는 못한다.
 * 점주 화면의 통계와 관리자 화면의 회원 목록에 보이는 데이터로 쓴다.
 */
INSERT INTO member (member_id, provider_user_id, nickname, status, last_login_at, created_at, updated_at,
                    location_opt_in, location_opt_in_at)
VALUES (9001, 'seed-member-9001', '시드회원1', 'ACTIVE', NOW(6), NOW(6), NOW(6), 1, NOW(6)),
       (9002, 'seed-member-9002', '시드회원2', 'ACTIVE', NOW(6), NOW(6), NOW(6), 1, NOW(6))
ON DUPLICATE KEY UPDATE member_id = member_id;

-- age_group 은 VARCHAR(10) 이라 AGE_50_PLUS(11자)는 들어가지 않는다
INSERT INTO member_profile (member_profile_id, member_id, gender, age_group, location_nickname, road_address,
                            latitude, longitude, onboarding_completed_at, created_at, updated_at)
VALUES (9001, 9001, 'MALE', 'AGE_20S', '회사', '서울 강남구 테헤란로 129', 37.4990000, 127.0320000, NOW(6), NOW(6), NOW(6)),
       (9002, 9002, 'FEMALE', 'AGE_30S', '회사', '서울 강남구 테헤란로 129', 37.4990000, 127.0320000, NOW(6), NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE member_profile_id = member_profile_id;
