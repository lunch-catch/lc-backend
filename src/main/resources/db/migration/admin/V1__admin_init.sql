-- admin 도메인 초기 스키마. 드라이브 ERD 폴더 전체_flyway.sql 의 1장과 같다
-- 공통 규칙(소프트 참조, CHECK, 시각, created_at/updated_at)은 같은 폴더의 README.md 를 따른다
-- 대상: MySQL 8.4, InnoDB, utf8mb4_0900_ai_ci

-- ############################################################################
-- 1. 관리자 (admin)    담당 정규동
-- ############################################################################

-- ----------------------------------------------------------------------------
-- admin
-- ----------------------------------------------------------------------------
CREATE TABLE admin (
    admin_id                  BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '관리자 PK',
    login_id                  VARCHAR(50)   NOT NULL                 COMMENT '관리자 로그인 ID',
    password_hash             VARCHAR(255)  NOT NULL                 COMMENT 'BCrypt 비밀번호 해시',
    name                      VARCHAR(50)   NOT NULL                 COMMENT '관리자 이름',
    role                      VARCHAR(30)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL  COMMENT '관리자 권한(ADMIN, SUPER_ADMIN)',
    status                    VARCHAR(30)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'ACTIVE'  COMMENT '관리자 상태(ACTIVE, DELETED)',
    refresh_token_hash        CHAR(64)                               COMMENT 'Refresh Token SHA-256 해시',
    refresh_token_expires_at  DATETIME(6)                            COMMENT 'Refresh Token 만료 시각',
    deleted_at                DATETIME(6)                            COMMENT '관리자 비활성화 시각',
    created_at                DATETIME(6)   NOT NULL,
    updated_at                DATETIME(6)   NOT NULL,

    PRIMARY KEY (admin_id),
    UNIQUE KEY uk_admin_login_id (login_id),
    KEY idx_admin_refresh_token_hash (refresh_token_hash),

    CONSTRAINT chk_admin_deleted CHECK ((status = 'DELETED' AND deleted_at IS NOT NULL AND refresh_token_hash IS NULL) OR (status <> 'DELETED' AND deleted_at IS NULL)),
    CONSTRAINT chk_admin_refresh_token CHECK ((refresh_token_hash IS NULL AND refresh_token_expires_at IS NULL) OR (refresh_token_hash IS NOT NULL AND refresh_token_expires_at IS NOT NULL)),
    CONSTRAINT chk_admin_role CHECK (role IN ('SUPER_ADMIN', 'ADMIN')),
    CONSTRAINT chk_admin_status CHECK (status IN ('ACTIVE', 'DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
