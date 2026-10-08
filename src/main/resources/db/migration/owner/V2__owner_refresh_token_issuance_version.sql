-- DB 커밋 순서대로 증가하는 점주 로그인 발급 순번이다.
ALTER TABLE owner
    ADD COLUMN refresh_token_issuance_version BIGINT NOT NULL DEFAULT 0 COMMENT '점주 로그인 토큰 발급 순번',
    ADD CONSTRAINT chk_owner_refresh_token_issuance_version CHECK (refresh_token_issuance_version >= 0);
