-- owner 도메인 초기 스키마. 드라이브 ERD 폴더 전체_flyway.sql 의 2장과 같다
-- 공통 규칙(소프트 참조, CHECK, 시각, created_at/updated_at)은 같은 폴더의 README.md 를 따른다
-- 대상: MySQL 8.4, InnoDB, utf8mb4_0900_ai_ci

-- ############################################################################
-- 2. 점주 (owner)    담당 정규동
-- ############################################################################

-- ----------------------------------------------------------------------------
-- owner
-- ----------------------------------------------------------------------------
CREATE TABLE owner (
    owner_id                  BIGINT        NOT NULL AUTO_INCREMENT        COMMENT '점주 PK',
    email                     VARCHAR(255)  NOT NULL                       COMMENT '점주 이메일. UNIQUE 제약 조건 적용',
    password_hash             VARCHAR(255)  NOT NULL                       COMMENT 'BCrypt 해싱한 점주 비밀번호',
    role                      VARCHAR(20)   NOT NULL DEFAULT 'OWNER'       COMMENT '점주 권한. 서버에서 자동으로 OWNER로 설정',
    status                    VARCHAR(20)   NOT NULL DEFAULT 'ONBOARDING'  COMMENT '점주 상태(ONBOARDING, ACTIVE, SUSPENDED, WITHDRAWN)',
    tutorial_viewed           BOOLEAN       NOT NULL DEFAULT 0             COMMENT '최초 로그인 튜토리얼 확인 여부',
    suspended_at              DATETIME(6)                                  COMMENT '정지 시각',
    suspension_reason         VARCHAR(500)                                 COMMENT '정지 사유',
    withdrawn_at              DATETIME(6)                                  COMMENT '탈퇴 시각',
    refresh_token_hash        CHAR(64)                                     COMMENT 'Refresh Token SHA-256 해시. 관계형 DB 백업용',
    refresh_token_expires_at  DATETIME(6)                                  COMMENT 'Refresh Token 만료 시각',
    last_login_at             DATETIME(6)                                  COMMENT '최근 로그인 시각',
    created_at                DATETIME(6)   NOT NULL                       COMMENT '생성일시',
    updated_at                DATETIME(6)   NOT NULL                       COMMENT '수정일시',

    PRIMARY KEY (owner_id),
    UNIQUE KEY uk_owner_email (email),

    CONSTRAINT chk_owner_refresh_token CHECK ((refresh_token_hash IS NULL AND refresh_token_expires_at IS NULL) OR (refresh_token_hash IS NOT NULL AND refresh_token_expires_at IS NOT NULL)),
    CONSTRAINT chk_owner_role CHECK (role = 'OWNER'),
    CONSTRAINT chk_owner_status CHECK (status IN ('ONBOARDING', 'ACTIVE', 'SUSPENDED', 'WITHDRAWN')),
    CONSTRAINT chk_owner_suspended CHECK (status <> 'SUSPENDED' OR suspended_at IS NOT NULL),
    CONSTRAINT chk_owner_withdrawn CHECK (status <> 'WITHDRAWN' OR withdrawn_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
