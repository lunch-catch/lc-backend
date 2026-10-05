-- coupon 도메인 초기 스키마. 드라이브 ERD 폴더 전체_flyway.sql 의 8장과 같다
-- 공통 규칙(소프트 참조, CHECK, 시각, created_at/updated_at)은 같은 폴더의 README.md 를 따른다
-- 대상: MySQL 8.4, InnoDB, utf8mb4_0900_ai_ci

-- ############################################################################
-- 8. 쿠폰 (coupon)    담당 최민혁
-- ############################################################################

-- ----------------------------------------------------------------------------
-- coupon_daily_limit
--   소프트 참조: member_id -> member(회원)
-- ----------------------------------------------------------------------------
CREATE TABLE coupon_daily_limit (
    coupon_daily_limit_id  BIGINT       NOT NULL AUTO_INCREMENT,
    member_id              BIGINT       NOT NULL,
    issued_count           INT          NOT NULL,
    business_date          DATE         NOT NULL,
    created_at             DATETIME(6)  NOT NULL,
    updated_at             DATETIME(6)  NOT NULL,

    PRIMARY KEY (coupon_daily_limit_id),
    UNIQUE KEY uk_coupon_daily_limit_member_date (member_id, business_date),

    CONSTRAINT chk_coupon_daily_limit_count CHECK (issued_count BETWEEN 0 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- coupon_event
--   소프트 참조: campaign_id -> campaign(캠페인)
-- ----------------------------------------------------------------------------
CREATE TABLE coupon_event (
    coupon_event_id    BIGINT       NOT NULL AUTO_INCREMENT,
    campaign_id        BIGINT       NOT NULL,
    business_date      DATE         NOT NULL,
    issue_quantity    INT          NOT NULL,
    usable_start_at  DATETIME(6)  NOT NULL,
    usable_end_at    DATETIME(6)  NOT NULL,
    status             VARCHAR(20)  NOT NULL                 COMMENT 'SCHEDULED/ACTIVE/PAUSED/ENDED. [2026-10-04] 캠페인이 발행하는 CampaignStatusChangedEvent, CampaignEndedEvent로만 바뀐다',
    status_changed_at  DATETIME(6)  NOT NULL                 COMMENT '마지막으로 반영한 캠페인 전이 시각. 이벤트의 changedAt이 이보다 늦을 때만 status를 바꾼다. 회차를 만들 때 캠페인의 status_changed_at으로 채운다',
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,

    PRIMARY KEY (coupon_event_id),
    UNIQUE KEY uk_coupon_event_campaign_date (campaign_id, business_date),

    CONSTRAINT chk_coupon_event_issue_quantity CHECK (issue_quantity > 0),
    CONSTRAINT chk_coupon_event_status CHECK (status IN ('SCHEDULED', 'ACTIVE', 'PAUSED', 'ENDED')),
    CONSTRAINT chk_coupon_event_usable_time CHECK (usable_end_at > usable_start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- coupon_inventory
-- ----------------------------------------------------------------------------
CREATE TABLE coupon_inventory (
    coupon_inventory_id  BIGINT       NOT NULL AUTO_INCREMENT,
    coupon_event_id             BIGINT       NOT NULL,
    sequence_no          INT          NOT NULL,
    coupon_code          BINARY(16)   NOT NULL,
    status               VARCHAR(20)  NOT NULL                 COMMENT 'AVAILABLE/ISSUED',
    usable_start_at    DATETIME(6)  NOT NULL,
    usable_end_at      DATETIME(6)  NOT NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,

    PRIMARY KEY (coupon_inventory_id),
    UNIQUE KEY uk_coupon_inventory_coupon_event_id_sequence_no (coupon_event_id, sequence_no),
    UNIQUE KEY uk_coupon_inventory_coupon_code (coupon_code),

    CONSTRAINT fk_coupon_inventory_event FOREIGN KEY (coupon_event_id) REFERENCES coupon_event (coupon_event_id),

    CONSTRAINT chk_coupon_inventory_sequence CHECK (sequence_no > 0),
    CONSTRAINT chk_coupon_inventory_status CHECK (status IN ('AVAILABLE', 'ISSUED')),
    CONSTRAINT chk_coupon_inventory_usable_time CHECK (usable_end_at > usable_start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- member_coupon
--   소프트 참조: member_id -> member(회원)
-- ----------------------------------------------------------------------------
CREATE TABLE member_coupon (
    member_coupon_id   BIGINT       NOT NULL AUTO_INCREMENT,
    coupon_event_id           BIGINT       NOT NULL,
    member_id          BIGINT       NOT NULL,
    coupon_code        BINARY(16)   NOT NULL,
    qr_version         INT          NOT NULL DEFAULT 0,
    qr_token           BINARY(16),
    qr_expires_at      DATETIME(6),
    status             VARCHAR(20)  NOT NULL                 COMMENT 'ISSUED/USED/EXPIRED/REMOVED',
    usable_start_at  DATETIME(6)  NOT NULL,
    usable_end_at    DATETIME(6)  NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,

    PRIMARY KEY (member_coupon_id),
    UNIQUE KEY uk_member_coupon_event_member (coupon_event_id, member_id),
    UNIQUE KEY uk_member_coupon_coupon_code (coupon_code),
    KEY idx_member_coupon_member (member_id),

    CONSTRAINT fk_member_coupon_event FOREIGN KEY (coupon_event_id) REFERENCES coupon_event (coupon_event_id),

    CONSTRAINT chk_member_coupon_status CHECK (status IN ('ISSUED', 'USED', 'EXPIRED', 'REMOVED')),
    CONSTRAINT chk_member_coupon_usable_time CHECK (usable_end_at > usable_start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- coupon_usage_history
--   소프트 참조: store_id -> store(가게)
-- ----------------------------------------------------------------------------
CREATE TABLE coupon_usage_history (
    coupon_usage_history_id  BIGINT       NOT NULL AUTO_INCREMENT,
    member_coupon_id         BIGINT       NOT NULL,
    store_id                 BIGINT       NOT NULL,
    discount_amount          INT          NOT NULL,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,

    PRIMARY KEY (coupon_usage_history_id),
    KEY fk_coupon_usage_history_member_coupon (member_coupon_id),
    KEY idx_coupon_usage_history_store (store_id),

    CONSTRAINT fk_coupon_usage_history_member_coupon FOREIGN KEY (member_coupon_id) REFERENCES member_coupon (member_coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
