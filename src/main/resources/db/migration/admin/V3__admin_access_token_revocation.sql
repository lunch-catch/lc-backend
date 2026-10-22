-- 관리자 로그아웃의 두 폐기 기준을 Redis와 무관하게 보존한다.
ALTER TABLE admin
    ADD COLUMN access_token_revoked_version BIGINT NOT NULL DEFAULT 0 COMMENT '로그아웃으로 폐기한 Access Token 순번 상한',
    ADD COLUMN access_token_valid_after DATETIME(6) NULL COMMENT '기존 관리자 JWT 폐기 기준 시각',
    ADD CONSTRAINT chk_admin_access_token_revoked_version CHECK (access_token_revoked_version >= 0 AND access_token_revoked_version <= refresh_token_issuance_version);

-- 기존 Redis 폐기 상태를 역으로 복원할 수 없으므로 배포 전 토큰을 안전하게 무효화한다.
UPDATE admin
   SET access_token_revoked_version = refresh_token_issuance_version,
       access_token_valid_after = CURRENT_TIMESTAMP(6);
