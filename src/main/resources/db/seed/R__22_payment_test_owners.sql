-- 결제 테스트용 점주 10개를 결제 이력 없이 생성한다(#674).
INSERT INTO owner (owner_id, email, password_hash, role, status, tutorial_viewed, created_at, updated_at)
VALUES
       (9019, 'owner19@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9020, 'owner20@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9021, 'owner21@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9022, 'owner22@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9023, 'owner23@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9024, 'owner24@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9025, 'owner25@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9026, 'owner26@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9027, 'owner27@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6)),
       (9028, 'owner28@seed.lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE owner_id = owner_id;
