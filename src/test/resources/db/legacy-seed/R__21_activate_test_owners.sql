-- 프론트 테스트용 ACTIVE 점주 계정 두 개를 생성한다.
INSERT INTO owner (owner_id, email, password_hash, role, status, tutorial_viewed, created_at, updated_at)
VALUES (9017, 'owner17@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9018, 'owner18@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE owner_id = owner_id;