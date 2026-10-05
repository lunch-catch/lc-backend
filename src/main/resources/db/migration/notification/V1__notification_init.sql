-- notification 도메인 초기 스키마. 드라이브 ERD 폴더 전체_flyway.sql 의 9장과 같다
-- 공통 규칙(소프트 참조, CHECK, 시각, created_at/updated_at)은 같은 폴더의 README.md 를 따른다
-- 대상: MySQL 8.4, InnoDB, utf8mb4_0900_ai_ci

-- ############################################################################
-- 9. 알림 (notification)    담당 정규동
-- ############################################################################

-- ----------------------------------------------------------------------------
-- notification_device
--   소프트 참조: member_id -> member(회원)
-- ----------------------------------------------------------------------------
CREATE TABLE notification_device (
    notification_device_id  BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '알림 대상 기기 PK',
    member_id               BIGINT        NOT NULL                 COMMENT 'Push 등록 정보를 소유한 사용자 ID',
    fid                     VARCHAR(255)  NOT NULL                 COMMENT 'Firebase Installation ID(FID)',
    last_synced_at          DATETIME(6)   NOT NULL                 COMMENT 'FID가 마지막으로 서버에 등록 또는 갱신된 시각',
    created_at              DATETIME(6)   NOT NULL                 COMMENT '생성일시',
    updated_at              DATETIME(6)   NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (notification_device_id),
    UNIQUE KEY uk_notification_device_fid (fid),
    KEY idx_notification_device_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- notification
--   소프트 참조: member_id -> member(회원), campaign_id -> campaign(캠페인)
-- ----------------------------------------------------------------------------
CREATE TABLE notification (
    notification_id    BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '알림 PK',
    member_id          BIGINT        NOT NULL                 COMMENT '알림 대상 사용자 ID',
    campaign_id        BIGINT        NOT NULL                 COMMENT '알림 대상 캠페인 ID',
    notification_type  VARCHAR(50)   NOT NULL                 COMMENT '알림 유형(CAMPAIGN_OPEN_REMINDER)',
    title              VARCHAR(100)  NOT NULL                 COMMENT '알림 제목',
    content            VARCHAR(500)  NOT NULL                 COMMENT '알림 내용',
    target_url         VARCHAR(500)                           COMMENT '알림 클릭 시 이동할 프론트 페이지 경로',
    business_date      DATE          NOT NULL                 COMMENT '알림 대상 날짜. 중복 발송 판단에 사용',
    read_at            DATETIME(6)                            COMMENT '알림 읽은 시각. NULL이면 안읽음',
    created_at         DATETIME(6)   NOT NULL                 COMMENT '생성일시',
    updated_at         DATETIME(6)   NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (notification_id),
    UNIQUE KEY uk_notification_member_campaign_type_date (member_id, campaign_id, notification_type, business_date),
    KEY idx_notification_campaign (campaign_id),
    KEY idx_notification_member_created_at (member_id, created_at),

    CONSTRAINT chk_notification_type CHECK (notification_type = 'CAMPAIGN_OPEN_REMINDER')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- notification_delivery
-- ----------------------------------------------------------------------------
CREATE TABLE notification_delivery (
    notification_delivery_id  BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '알림 발송 정보 PK',
    notification_id           BIGINT        NOT NULL                 COMMENT '알림 PK',
    notification_device_id    BIGINT        NOT NULL                 COMMENT '알림 대상 기기 PK',
    status                    VARCHAR(20)   NOT NULL                 COMMENT '발송 상태(PENDING, PROCESSING, SENT, RETRY, FAILED, EXPIRED)',
    attempt_count             INT           NOT NULL DEFAULT 0       COMMENT 'FCM 실제 발송 시도 횟수. 최초 발송을 포함하며 재시도는 최대 2회',
    next_attempt_at           DATETIME(6)                            COMMENT '다음 재시도 예정 시각',
    last_attempt_at           DATETIME(6)                            COMMENT '마지막 FCM 발송 시도 시각',
    sent_at                   DATETIME(6)                            COMMENT 'FCM 발송 요청 성공 시각',
    last_error_code           VARCHAR(100)                           COMMENT '마지막 발송 실패 오류 코드',
    last_error_message        VARCHAR(500)                           COMMENT '마지막 발송 실패 오류 내용',
    provider_message_id       VARCHAR(255)                           COMMENT 'FCM 발송 성공 시 반환된 메시지 식별자',
    created_at                DATETIME(6)   NOT NULL                 COMMENT '생성일시',
    updated_at                DATETIME(6)   NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (notification_delivery_id),
    UNIQUE KEY uk_notification_delivery_notification_device (notification_id, notification_device_id),
    KEY fk_notification_delivery_device (notification_device_id),
    KEY idx_notification_delivery_status_retry (status, next_attempt_at),

    CONSTRAINT fk_notification_delivery_device FOREIGN KEY (notification_device_id) REFERENCES notification_device (notification_device_id),
    CONSTRAINT fk_notification_delivery_notification FOREIGN KEY (notification_id) REFERENCES notification (notification_id),

    CONSTRAINT chk_notification_delivery_attempt CHECK (attempt_count BETWEEN 0 AND 3),
    CONSTRAINT chk_notification_delivery_sent CHECK ((status = 'SENT' AND sent_at IS NOT NULL) OR (status <> 'SENT' AND sent_at IS NULL)),
    CONSTRAINT chk_notification_delivery_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'RETRY', 'FAILED', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- notification_sent
-- ----------------------------------------------------------------------------
CREATE TABLE notification_sent (
    notification_sent_id      BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '알림 발송 성공 이벤트 PK',
    notification_delivery_id  BIGINT       NOT NULL                 COMMENT '발송 성공한 알림 발송 정보 ID',
    created_at                DATETIME(6)  NOT NULL                 COMMENT '생성일시',
    updated_at                DATETIME(6)  NOT NULL,

    PRIMARY KEY (notification_sent_id),
    UNIQUE KEY uk_notification_sent_delivery (notification_delivery_id),

    CONSTRAINT fk_notification_sent_delivery FOREIGN KEY (notification_delivery_id) REFERENCES notification_delivery (notification_delivery_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- notification_outbox
-- ----------------------------------------------------------------------------
CREATE TABLE notification_outbox (
    notification_outbox_id                 BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '알림 Outbox PK',
    notification_delivery_id  BIGINT        NOT NULL                 COMMENT '발송할 알림 발송 정보 ID',
    event_type                VARCHAR(50)   NOT NULL                 COMMENT 'Outbox 이벤트 유형(NOTIFICATION_SEND)',
    status                    VARCHAR(20)   NOT NULL                 COMMENT 'Outbox 처리 상태(PENDING, PROCESSING, RETRY, PROCESSED, FAILED)',
    attempt_count             INT           NOT NULL DEFAULT 0       COMMENT 'Outbox 처리 시도 횟수',
    next_attempt_at           DATETIME(6)                            COMMENT '다음 처리 예정 시각',
    last_error_code           VARCHAR(100)                           COMMENT '마지막 처리 오류 코드',
    processed_at              DATETIME(6)                            COMMENT 'Outbox 처리 완료 시각',
    created_at                DATETIME(6)   NOT NULL                 COMMENT '생성일시',
    updated_at                DATETIME(6)   NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (notification_outbox_id),
    UNIQUE KEY uk_notification_outbox_delivery (notification_delivery_id),
    KEY idx_notification_outbox_status_retry (status, next_attempt_at),

    CONSTRAINT fk_notification_outbox_delivery FOREIGN KEY (notification_delivery_id) REFERENCES notification_delivery (notification_delivery_id),

    CONSTRAINT chk_notification_outbox_event_type CHECK (event_type = 'NOTIFICATION_SEND'),
    CONSTRAINT chk_notification_outbox_processed CHECK ((status = 'PROCESSED' AND processed_at IS NOT NULL) OR (status <> 'PROCESSED' AND processed_at IS NULL)),
    CONSTRAINT chk_notification_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY', 'PROCESSED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
