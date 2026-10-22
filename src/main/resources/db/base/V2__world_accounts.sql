/*
 * 기본 세계의 계정을 넣는다 (#741). 관리자, 점주, 회원과 그 곁 표다.
 * 번호는 B = 10000000 을 더한 고정 번호이고 값은 WORLD_SPEC 의 공식으로 계산한다.
 * 진행 단계가 LOCATION 이상인 점주의 store_registration_progress 는 store 외래 키 때문에 가게를 만드는 V3 에서 넣는다.
 * superadmin, demo-admin 은 시연 층(R__demo)이 넣는다.
 */

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

-- 관리자

-- ADM.ADMIN_ACTIVE, ADM.MANY: world-admin-01 ~ 25, 가입일 D-60 + n 일
INSERT INTO admin (admin_id, login_id, password_hash, name, role, status, deleted_at, created_at, updated_at)
SELECT 10000000 + n,
       CONCAT('world-admin-', LPAD(n, 2, '0')),
       '${scenarioPasswordHash}',
       CONCAT('운영관리자', LPAD(n, 2, '0')),
       'ADMIN',
       'ACTIVE',
       NULL,
       TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 60 - n DAY), '10:00:00'),
       TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 60 - n DAY), '10:00:00')
FROM base_n
WHERE n BETWEEN 1 AND 25
ON DUPLICATE KEY UPDATE admin_id = admin_id;

-- ADM.DELETED
INSERT INTO admin (admin_id, login_id, password_hash, name, role, status, deleted_at, created_at, updated_at)
VALUES (10000026, 'world-admin-deleted', '${scenarioPasswordHash}', '삭제된관리자', 'ADMIN', 'DELETED',
        TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 10 DAY), '10:00:00'),
        TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 40 DAY), '10:00:00'),
        TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 10 DAY), '10:00:00'))
ON DUPLICATE KEY UPDATE admin_id = admin_id;

-- ADM.SUPER_ADMIN_DEMOTED: 로그인한 뒤 role 이나 status 를 바꿔 보는 SUPER_ADMIN
INSERT INTO admin (admin_id, login_id, password_hash, name, role, status, deleted_at, created_at, updated_at)
VALUES (10000027, 'world-superadmin-demoted', '${scenarioPasswordHash}', '강등시험관리자', 'SUPER_ADMIN', 'ACTIVE', NULL,
        TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 30 DAY), '10:00:00'),
        TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 30 DAY), '10:00:00'))
ON DUPLICATE KEY UPDATE admin_id = admin_id;

-- 점주

/*
 * OWN.ACTIVE, OWN.ACTIVE_TUTORIAL_UNSEEN, OWN.MANY_MIXED, OWN.SUSPENDED, OWN.WITHDRAWN, OWN.ONBOARDING_TERMS 와 그 뒤 단계.
 * 1 ~ 250 ACTIVE, 251 ~ 290 ONBOARDING, 291 ~ 295 SUSPENDED, 296 ~ 300 WITHDRAWN 이다.
 */
INSERT INTO owner (owner_id, email, password_hash, role, status, tutorial_viewed, suspended_at, suspension_reason,
                   withdrawn_at, last_login_at, created_at, updated_at)
SELECT 10000000 + n,
       CONCAT('world-owner-', LPAD(n, 3, '0'), '@world.lunchcatch.com'),
       '${scenarioPasswordHash}',
       'OWNER',
       CASE
           WHEN n <= 250 THEN 'ACTIVE'
           WHEN n <= 290 THEN 'ONBOARDING'
           WHEN n <= 295 THEN 'SUSPENDED'
           ELSE 'WITHDRAWN'
       END,
       CASE
           WHEN n <= 250 THEN IF(n % 50 = 0, 0, 1)
           WHEN n <= 290 THEN 0
           ELSE 1
       END,
       IF(n BETWEEN 291 AND 295, TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 5 DAY), '10:00:00'), NULL),
       IF(n BETWEEN 291 AND 295, '시나리오 정지', NULL),
       IF(n BETWEEN 296 AND 300, TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 3 DAY), '10:00:00'), NULL),
       CASE
           WHEN n <= 250 THEN TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 1 DAY), '09:00:00') + INTERVAL n % 9 HOUR
           WHEN n <= 290 THEN TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 120 - n % 110 DAY), '10:05:00')
           WHEN n <= 295 THEN TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 6 DAY), '11:00:00')
           ELSE TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 4 DAY), '11:00:00')
       END,
       TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 120 - n % 110 DAY), '10:00:00'),
       CASE
           WHEN n BETWEEN 291 AND 295 THEN TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 5 DAY), '10:00:00')
           WHEN n BETWEEN 296 AND 300 THEN TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 3 DAY), '10:00:00')
           ELSE TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 120 - n % 110 DAY), '10:00:00')
       END
FROM base_n
WHERE n BETWEEN 1 AND 300
ON DUPLICATE KEY UPDATE owner_id = owner_id;

-- 입점 진행(store_registration_progress)은 모든 단계를 V3 이 넣는다

-- 회원

/*
 * MEM.ONBOARDED, MEM.LOCATION_OPT_OUT, MEM.MANY_MIXED, MEM.NOT_ONBOARDED, MEM.SUSPENDED, MEM.WITHDRAWN.
 * 1 ~ 2950 ACTIVE, 2951 ~ 2980 SUSPENDED, 2981 ~ 3000 WITHDRAWN 이다.
 * 가입일은 D-(1 + n % 60) 이고 탈퇴 회원은 탈퇴일(D-2) 보다 앞서도록 D-3 보다 늦지 않게 둔다.
 */
INSERT INTO member (member_id, provider_user_id, nickname, status, last_login_at, created_at, updated_at, withdrawn_at,
                    notification_opt_in, location_opt_in, location_opt_in_at, suspended_at, suspended_until,
                    suspension_reason)
SELECT 10000000 + n,
       IF(n <= 2980, CONCAT('world-member-', LPAD(n, 4, '0')), NULL),
       IF(n <= 2980, CONCAT('회원', n), '탈퇴한 회원'),
       CASE
           WHEN n <= 2950 THEN 'ACTIVE'
           WHEN n <= 2980 THEN 'SUSPENDED'
           ELSE 'WITHDRAWN'
       END,
       IF(n <= 2850 AND n % 10 <> 0,
          TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 1 DAY), '11:30:00') + INTERVAL n % 120 MINUTE, NULL),
       TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL IF(n > 2980, GREATEST(1 + n % 60, 3), 1 + n % 60) DAY), '09:00:00')
           + INTERVAL n % 480 MINUTE,
       TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL IF(n > 2980, GREATEST(1 + n % 60, 3), 1 + n % 60) DAY), '09:00:00')
           + INTERVAL n % 480 MINUTE,
       CASE
           WHEN n <= 2980 THEN NULL
           WHEN n = 2983 THEN TIMESTAMP('${baseDate}', '08:00:00')
           ELSE TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 2 DAY), '20:00:00')
       END,
       0,
       IF(n <= 2850 AND n % 57 <> 0, 1, 0),
       IF(n <= 2850 AND n % 57 <> 0,
          TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 1 + n % 60 DAY), '09:00:00') + INTERVAL n % 480 + 10 MINUTE, NULL),
       CASE
           WHEN n BETWEEN 2951 AND 2980 THEN TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 1 DAY), '10:00:00')
           WHEN n = 2982 THEN TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 20 DAY), '10:00:00')
           ELSE NULL
       END,
       IF(n BETWEEN 2951 AND 2980 AND n % 2 = 0, TIMESTAMP(DATE_ADD('${baseDate}', INTERVAL 7 DAY), '10:00:00'), NULL),
       IF(n BETWEEN 2951 AND 2980, '시나리오 정지', NULL)
FROM base_n
WHERE n BETWEEN 1 AND 3000
ON DUPLICATE KEY UPDATE member_id = member_id;

/*
 * MEM.ONBOARDED, MEM.LOCATION_SET: 1 ~ 2850 과 정지 회원 2951 ~ 2980 은 온보딩을 마친 프로필이다.
 * 위치는 강남역 둘레 r = (n * 13) % 2500 m, 각도 (n * 71) % 360 도다.
 * 별칭과 도로명은 n % 10 = 1 이면 별칭만 비우고 n % 10 = 2 이면 둘 다 비워 좌표만 남긴다.
 */
INSERT INTO member_profile (member_profile_id, member_id, gender, age_group, location_nickname, road_address, latitude,
                            longitude, onboarding_completed_at, created_at, updated_at)
SELECT 10000000 + n,
       10000000 + n,
       IF(n % 20 = 0, 'OTHER', ELT(n % 2 + 1, 'MALE', 'FEMALE')),
       ELT(n % 4 + 1, 'AGE_20S', 'AGE_30S', 'AGE_40S', 'AGE_50_PLUS'),
       IF(n % 10 IN (1, 2), NULL, ELT(n % 2 + 1, '회사', '집')),
       IF(n % 10 = 2, NULL, CONCAT('서울 강남구 회원로 ', n)),
       ROUND(37.4979000 + ((n * 13) % 2500) * COS(RADIANS((n * 71) % 360)) / 111000, 7),
       ROUND(127.0276000 + ((n * 13) % 2500) * SIN(RADIANS((n * 71) % 360)) / 88200, 7),
       TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 1 + n % 60 DAY), '09:00:00') + INTERVAL n % 480 + 10 MINUTE,
       TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 1 + n % 60 DAY), '09:00:00') + INTERVAL n % 480 + 10 MINUTE,
       TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 1 + n % 60 DAY), '09:00:00') + INTERVAL n % 480 + 10 MINUTE
FROM base_n
WHERE n BETWEEN 1 AND 2850 OR n BETWEEN 2951 AND 2980
ON DUPLICATE KEY UPDATE member_profile_id = member_profile_id;

-- MEM.NOT_ONBOARDED, MEM.PROFILE_NO_DEMOGRAPHIC: 2851 ~ 2950 중 짝수는 위치만 있고 성별과 연령대가 빈 프로필이다
INSERT INTO member_profile (member_profile_id, member_id, gender, age_group, location_nickname, road_address, latitude,
                            longitude, onboarding_completed_at, created_at, updated_at)
SELECT 10000000 + n,
       10000000 + n,
       NULL,
       NULL,
       NULL,
       CONCAT('서울 강남구 회원로 ', n),
       ROUND(37.4979000 + ((n * 13) % 2500) * COS(RADIANS((n * 71) % 360)) / 111000, 7),
       ROUND(127.0276000 + ((n * 13) % 2500) * SIN(RADIANS((n * 71) % 360)) / 88200, 7),
       NULL,
       TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 1 + n % 60 DAY), '09:00:00') + INTERVAL n % 480 + 5 MINUTE,
       TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 1 + n % 60 DAY), '09:00:00') + INTERVAL n % 480 + 5 MINUTE
FROM base_n
WHERE n BETWEEN 2851 AND 2950 AND n % 2 = 0
ON DUPLICATE KEY UPDATE member_profile_id = member_profile_id;

/*
 * MEM.WITHDRAWN, MEM.WITHDRAWN_TODAY, MEM.REJOINED, MEM.SUSPENDED_HISTORY_WITHDRAWN: 탈퇴 회원마다 이력 1행.
 * 2981 의 카카오 번호는 D-1 에 가입한 ACTIVE 회원 2820 의 번호라서 2820 이 탈퇴 뒤 다시 가입한 사람이 된다.
 * 2982 는 정지 이력을 가진 채 탈퇴했고 2983 은 오늘 탈퇴했다.
 */
INSERT INTO member_withdrawal_history (member_withdrawal_history_id, member_id, provider_user_id, withdrawn_at,
                                       suspended_at, created_at, updated_at)
SELECT 10000000 + n,
       10000000 + n,
       IF(n = 2981, 'world-member-2820', CONCAT('world-member-', LPAD(n, 4, '0'))),
       IF(n = 2983, TIMESTAMP('${baseDate}', '08:00:00'), TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 2 DAY), '20:00:00')),
       IF(n = 2982, TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 20 DAY), '10:00:00'), NULL),
       IF(n = 2983, TIMESTAMP('${baseDate}', '08:00:00'), TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 2 DAY), '20:00:00')),
       IF(n = 2983, TIMESTAMP('${baseDate}', '08:00:00'), TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 2 DAY), '20:00:00'))
FROM base_n
WHERE n BETWEEN 2981 AND 3000
ON DUPLICATE KEY UPDATE member_withdrawal_history_id = member_withdrawal_history_id;

/*
 * 카카오 연결 해제 실패 행이다.
 * MEM.UNLINK_FAILURE_PENDING 2999, 재시도 한도를 다 쓴 EXHAUSTED 3000.
 * MEM.UNLINK_FAILURE_ATTEMPT4 2998, MEM.UNLINK_FAILURE_SKIPPED 2997(REJECTED 로 해결) 과 2996(다른 작업이 선점).
 * MEM.UNLINK_FAILURE_REACTIVATED 는 ACTIVE 회원 2849 에 남은 행이다.
 */
INSERT INTO kakao_unlink_failure (kakao_unlink_failure_id, member_id, provider_user_id, attempt_count, resolved,
                                  stop_reason, processing, created_at, updated_at)
SELECT 10000000 + n,
       10000000 + n,
       CONCAT('world-member-', LPAD(n, 4, '0')),
       CASE n WHEN 2999 THEN 1 WHEN 3000 THEN 5 WHEN 2998 THEN 4 WHEN 2997 THEN 2 WHEN 2996 THEN 1 ELSE 1 END,
       IF(n IN (3000, 2997), 1, 0),
       CASE n WHEN 3000 THEN 'EXHAUSTED' WHEN 2997 THEN 'REJECTED' ELSE NULL END,
       IF(n = 2996, 1, 0),
       IF(n = 2849, TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 5 DAY), '20:00:00'),
          TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 2 DAY), '20:00:01')),
       CASE
           WHEN n = 2849 THEN TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 5 DAY), '20:00:00')
           WHEN n IN (2999, 2996) THEN TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 2 DAY), '20:00:01')
           ELSE TIMESTAMP(DATE_SUB('${baseDate}', INTERVAL 1 DAY), '03:00:00')
       END
FROM base_n
WHERE n IN (2849, 2996, 2997, 2998, 2999, 3000)
ON DUPLICATE KEY UPDATE kakao_unlink_failure_id = kakao_unlink_failure_id;

DROP TEMPORARY TABLE base_n;
