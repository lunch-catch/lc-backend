-- ops 도메인 초기 스키마. 드라이브 ERD 폴더 전체_flyway.sql 의 11장과 같다
-- 공통 규칙(소프트 참조, CHECK, 시각, created_at/updated_at)은 같은 폴더의 README.md 를 따른다
-- 대상: MySQL 8.4, InnoDB, utf8mb4_0900_ai_ci

-- ############################################################################
-- 11. 운영 (ops)    담당 최재웅
-- ############################################################################

-- ----------------------------------------------------------------------------
-- platform_setting
--   소프트 참조: updated_by -> admin(관리자)
-- ----------------------------------------------------------------------------
CREATE TABLE platform_setting (
    platform_setting_id  BIGINT        NOT NULL AUTO_INCREMENT,
    setting_key          VARCHAR(50)   NOT NULL                 COMMENT 'IMPRESSION_VALID_SECONDS, SLOT_RATIO_ALLOCATION, SLOT_RATIO_RELEVANCE, RELEVANCE_WEIGHT',
    setting_value        VARCHAR(500)  NOT NULL                 COMMENT '단일 값 또는 콤마 구분 목록',
    description          VARCHAR(200),
    updated_by           BIGINT                                 COMMENT '관리자 ID',
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,

    PRIMARY KEY (platform_setting_id),
    UNIQUE KEY uk_platform_setting_setting_key (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- platform_setting_history
--   소프트 참조: changed_by -> admin(관리자)
-- ----------------------------------------------------------------------------
CREATE TABLE platform_setting_history (
    platform_setting_history_id  BIGINT        NOT NULL AUTO_INCREMENT,
    setting_key                  VARCHAR(50)   NOT NULL,
    before_value                 VARCHAR(500)                           COMMENT '최초 등록 시 NULL',
    after_value                  VARCHAR(500)  NOT NULL,
    changed_by                   BIGINT                                 COMMENT '관리자 ID',
    created_at                   DATETIME(6)   NOT NULL,
    updated_at                   DATETIME(6)   NOT NULL,

    PRIMARY KEY (platform_setting_history_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- batch_execution_log
-- ----------------------------------------------------------------------------
CREATE TABLE batch_execution_log (
    batch_execution_log_id  BIGINT        NOT NULL AUTO_INCREMENT,
    job_name                VARCHAR(50)   NOT NULL                 COMMENT '실행 대상 작업 식별자. 00:00 묶음 점유는 DAILY_0000, 캠페인 단위 작업은 작업명:캠페인 id',
    business_date           DATE          NOT NULL                 COMMENT '처리 대상 날짜(실행 시각 아님)',
    status                  VARCHAR(20)   NOT NULL                 COMMENT 'RUNNING/SUCCESS/FAILED',
    finished_at             DATETIME(6)                            COMMENT '미완료 시 NULL',
    failure_reason          VARCHAR(500)                           COMMENT 'FAILED일 때만',
    processed_count         INT,
    owner_id                VARCHAR(100)  NOT NULL                 COMMENT '[2026-10-05 신규] 실행 중인 배치 서버 식별자(호스트명). 이어받으면 새 서버로 바뀐다',
    created_at              DATETIME(6)   NOT NULL,
    updated_at              DATETIME(6)   NOT NULL                 COMMENT 'RUNNING 동안 30초마다 갱신하는 생존 신호. 2분 넘게 갱신이 없으면 다른 서버가 이어받는다',

    PRIMARY KEY (batch_execution_log_id),
    UNIQUE KEY uk_batch_execution_log_job_name_business_date (job_name, business_date),

    CONSTRAINT chk_batch_failure_reason CHECK ((status = 'FAILED' AND failure_reason IS NOT NULL) OR (status <> 'FAILED' AND failure_reason IS NULL)),
    CONSTRAINT chk_batch_finished CHECK ((status = 'RUNNING' AND finished_at IS NULL) OR (status <> 'RUNNING' AND finished_at IS NOT NULL AND finished_at >= created_at)),
    CONSTRAINT chk_batch_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- audit_log  |  관리 행위 감사 로그(104행). [2026-10-04] 관리자 도메인에서 운영 도메인으로 이동
--   소프트 참조: admin_id -> admin(관리자)
-- ----------------------------------------------------------------------------
CREATE TABLE audit_log (
    audit_log_id  BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '감사 로그 PK',
    admin_id      BIGINT        NOT NULL                 COMMENT '행위 관리자 ID. 소프트 참조(관리자 도메인)',
    action        VARCHAR(50)   NOT NULL                 COMMENT '관리자 수행 행위',
    target        VARCHAR(100)                           COMMENT '행위 대상',
    detail        TEXT                                   COMMENT '상세 내용',
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,

    PRIMARY KEY (audit_log_id),
    KEY idx_audit_log_admin (admin_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
