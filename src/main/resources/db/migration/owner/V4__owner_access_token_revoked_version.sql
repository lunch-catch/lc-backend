-- DB에서 점주 AT 폐기를 검증하며 -1은 폐기 이력이 없음을 나타낸다.
ALTER TABLE owner
    ADD COLUMN access_token_revoked_version BIGINT NOT NULL DEFAULT -1,
    ADD CONSTRAINT chk_owner_access_token_revoked_version CHECK (access_token_revoked_version >= -1);

-- 기존 계정은 현재 RT의 순번만 보존하고 과거 AT가 다시 유효해지지 않도록 차단한다.
UPDATE owner
SET access_token_revoked_version = CASE
    WHEN refresh_token_hash IS NULL THEN refresh_token_issuance_version
    ELSE refresh_token_issuance_version - 1
END
WHERE refresh_token_issuance_version > 0;
