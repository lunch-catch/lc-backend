-- 기존 단일 기기 토큰은 admin에 남기고 신규 로그인부터 세션별로 저장한다.
CREATE TABLE admin_login_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    admin_id BIGINT NOT NULL,
    issuance_version BIGINT NOT NULL,
    role VARCHAR(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    refresh_token_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    refresh_token_version BIGINT NOT NULL DEFAULT 1,
    refresh_revoked BOOLEAN NOT NULL DEFAULT FALSE,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_login_session_version (admin_id, issuance_version),
    UNIQUE KEY uk_admin_login_session_hash (refresh_token_hash),
    CONSTRAINT chk_admin_login_session_token_version CHECK (refresh_token_version > 0),
    CONSTRAINT chk_admin_login_session_version CHECK (issuance_version > 0),
    CONSTRAINT chk_admin_login_session_role CHECK (role IN ('ADMIN', 'SUPER_ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
