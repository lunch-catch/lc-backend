-- 계정 전체 폐기와 이전 단일 토큰의 폐기를 구분한다.
ALTER TABLE owner
    ADD COLUMN session_generation BIGINT NOT NULL DEFAULT 0 COMMENT '전체 세션 폐기 세대',
    ADD CONSTRAINT chk_owner_session_generation CHECK (session_generation >= 0);

CREATE TABLE owner_session (
    owner_session_id BIGINT NOT NULL AUTO_INCREMENT,
    session_key VARCHAR(36) NOT NULL COMMENT 'JWT에 서명하는 세션 식별자',
    owner_id BIGINT NOT NULL COMMENT '점주 ID. 소프트 참조',
    generation BIGINT NOT NULL COMMENT '로그인 시점의 전체 세션 폐기 세대',
    rotation_version BIGINT NOT NULL DEFAULT 1 COMMENT '세션별 RT 게시 및 폐기 순번',
    refresh_token_hash CHAR(64) NOT NULL,
    previous_token_hash CHAR(64) NULL,
    previous_valid_until DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_session_id),
    UNIQUE KEY uk_owner_session_key (session_key),
    UNIQUE KEY uk_owner_session_refresh_token_hash (refresh_token_hash),
    KEY idx_owner_session_previous_token_hash (previous_token_hash),
    KEY idx_owner_session_owner_id (owner_id, expires_at),
    CONSTRAINT chk_owner_session_rotation_version CHECK (rotation_version > 0),
    CONSTRAINT chk_owner_session_generation_value CHECK (generation >= 0),
    CONSTRAINT chk_owner_session_previous_token CHECK (
        (previous_token_hash IS NULL AND previous_valid_until IS NULL)
        OR (previous_token_hash IS NOT NULL AND previous_valid_until IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
