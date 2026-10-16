-- 프론트 테스트용 기존 점주 두 계정이 온보딩 중이면 활성화한다.
UPDATE owner
SET status = 'ACTIVE',
    updated_at = NOW(6)
WHERE email IN (
    'owner001@lunchcatch.com',
    'owner002@lunchcatch.com'
)
AND status = 'ONBOARDING';