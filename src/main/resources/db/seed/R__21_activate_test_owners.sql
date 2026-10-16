-- 테스트용 점주가 없으면 일반 가입과 같은 온보딩 상태로 생성하고 기존 계정 정보는 유지한다.
INSERT INTO owner (owner_id, email, password_hash, role, status, tutorial_viewed, created_at, updated_at)
VALUES (9017, 'owner001@lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ONBOARDING', 0, NOW(6), NOW(6)),
       (9018, 'owner002@lunchcatch.com', '${seedPasswordHash}', 'OWNER', 'ONBOARDING', 0, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE owner_id = owner_id;

-- 프론트 테스트용 기존 점주 두 계정이 온보딩 중이면 활성화한다.
UPDATE owner
SET status = 'ACTIVE',
    updated_at = NOW(6)
WHERE email IN (
    'owner001@lunchcatch.com',
    'owner002@lunchcatch.com'
)
AND status = 'ONBOARDING';