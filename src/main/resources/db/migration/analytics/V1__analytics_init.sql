-- analytics 도메인 초기 스키마. 드라이브 ERD 폴더 전체_flyway.sql 의 10장과 같다
-- 공통 규칙(소프트 참조, CHECK, 시각, created_at/updated_at)은 같은 폴더의 README.md 를 따른다
-- 대상: MySQL 8.4, InnoDB, utf8mb4_0900_ai_ci

-- ############################################################################
-- 10. 분석 (analytics)    담당 김현우
-- ############################################################################

-- ----------------------------------------------------------------------------
-- daily_campaign_not_billed_analytics  |  캠페인과 사유별 일별 미과금 노출 수. deduction_result(정산 소유).billing_status의 미과금과 impression_log(광고 서빙 소유).sold_out을 serve_id로 합쳐 집계
--   소프트 참조: campaign_id -> campaign(캠페인)
-- ----------------------------------------------------------------------------
CREATE TABLE daily_campaign_not_billed_analytics (
    business_date               DATE         NOT NULL,
    campaign_id        BIGINT       NOT NULL            COMMENT '소프트 참조',
    not_billed_reason  VARCHAR(30)  NOT NULL,
    not_billed_count   BIGINT       NOT NULL DEFAULT 0,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,

    PRIMARY KEY (business_date, campaign_id, not_billed_reason),

    CONSTRAINT chk_daily_campaign_not_billed_nonneg CHECK (not_billed_count >= 0),
    CONSTRAINT chk_daily_campaign_not_billed_reason CHECK (not_billed_reason IN ('NOT_BILLED_BUDGET', 'NOT_BILLED_PAUSED', 'NOT_BILLED_SOLD_OUT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='캠페인과 사유별 일별 미과금 노출 수. deduction_result(정산 소유).billing_status의 미과금과 impression_log(광고 서빙 소유).sold_out을 serve_id로 합쳐 집계';

-- ----------------------------------------------------------------------------
-- daily_invalid_analytics  |  사유코드별 일별 무효 노출 지표. [2026-10-01] member_id 축 신규 추가, surrogate PK로 변경(campaign_id/member_id 조합별 분해 허용). [13차 확정] rate_limit_429_count 제거. 피드 429 집계는 운영 모니터링(Grafana)으로 대체
--   소프트 참조: campaign_id -> campaign(캠페인), member_id -> member(회원)
-- ----------------------------------------------------------------------------
CREATE TABLE daily_invalid_analytics (
    daily_invalid_analytics_id     BIGINT       NOT NULL AUTO_INCREMENT,
    business_date                           DATE         NOT NULL,
    reason_code                    VARCHAR(50)  NOT NULL,
    campaign_id                    BIGINT                                COMMENT '캠페인별 분해 시에만 값 존재. NULL이면 플랫폼 전체 합계 행',
    member_id                      BIGINT                                COMMENT '[2026-10-01 신규] 사용자별 분해 시에만 값 존재(A-09 memberId 필터). NULL이면 해당 축 미분해',
    invalid_count                  BIGINT       NOT NULL DEFAULT 0,
    concentration_suspected  BOOLEAN      NOT NULL DEFAULT 0,
    created_at                     DATETIME(6)  NOT NULL,
    updated_at                     DATETIME(6)  NOT NULL,

    PRIMARY KEY (daily_invalid_analytics_id),
    KEY idx_daily_invalid_analytics_business_date_campaign (business_date, campaign_id, reason_code),
    KEY idx_daily_invalid_analytics_business_date_member (business_date, member_id, reason_code),

    CONSTRAINT chk_daily_invalid_analytics_nonneg CHECK (invalid_count >= 0),
    CONSTRAINT chk_daily_invalid_analytics_reason_code CHECK (reason_code IN ('EXPIRED_OR_UNKNOWN', 'NOT_OWNER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='사유코드별 일별 무효 노출 지표. [2026-10-01] member_id 축 신규 추가, surrogate PK로 변경(campaign_id/member_id 조합별 분해 허용). [13차 확정] rate_limit_429_count 제거. 피드 429 집계는 운영 모니터링(Grafana)으로 대체';

-- ----------------------------------------------------------------------------
-- daily_member_analytics  |  일별 회원 지표
-- ----------------------------------------------------------------------------
CREATE TABLE daily_member_analytics (
    business_date                     DATE  NOT NULL,
    new_member_count         INT   NOT NULL DEFAULT 0,
    cumulative_member_count  INT   NOT NULL DEFAULT 0,
    active_member_count      INT   NOT NULL DEFAULT 0,
    withdrawn_count          INT   NOT NULL DEFAULT 0,
    suspended_count          INT   NOT NULL DEFAULT 0,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,

    PRIMARY KEY (business_date),

    CONSTRAINT chk_daily_member_analytics_nonneg CHECK (new_member_count >= 0 AND cumulative_member_count >= 0 AND active_member_count >= 0 AND withdrawn_count >= 0 AND suspended_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='일별 회원 지표';

-- ----------------------------------------------------------------------------
-- daily_owner_spend  |  점주별 일별 소진액(A-08 상위 소진 목록). [13차 확정] 신규
--   소프트 참조: owner_id -> owner(점주)
-- ----------------------------------------------------------------------------
CREATE TABLE daily_owner_spend (
    business_date          DATE    NOT NULL,
    owner_id      BIGINT  NOT NULL            COMMENT '소프트 참조',
    spent_amount  BIGINT  NOT NULL DEFAULT 0  COMMENT 'DEDUCT 합의 절댓값',
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,

    PRIMARY KEY (business_date, owner_id),

    CONSTRAINT chk_daily_owner_spend_nonneg CHECK (spent_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='점주별 일별 소진액(A-08 상위 소진 목록). [13차 확정] 신규';

-- ----------------------------------------------------------------------------
-- daily_segment_analytics  |  카테고리/지역별 일별 분해 지표
-- ----------------------------------------------------------------------------
CREATE TABLE daily_segment_analytics (
    business_date              DATE         NOT NULL,
    category_code          VARCHAR(50)  NOT NULL,
    region            VARCHAR(50)  NOT NULL,
    impression_count  BIGINT       NOT NULL DEFAULT 0,
    wish_count        BIGINT       NOT NULL DEFAULT 0,
    issued_count       BIGINT       NOT NULL DEFAULT 0,
    redeem_count      BIGINT       NOT NULL DEFAULT 0,
    spent_amount      BIGINT       NOT NULL DEFAULT 0,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,

    PRIMARY KEY (business_date, category_code, region),

    CONSTRAINT chk_daily_segment_analytics_nonneg CHECK (impression_count >= 0 AND wish_count >= 0 AND issued_count >= 0 AND redeem_count >= 0 AND spent_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='카테고리/지역별 일별 분해 지표';

-- ----------------------------------------------------------------------------
-- daily_settlement_analytics  |  일별 정산 지표. [2026-10-01] reserved_amount/released_amount/adjust_amount 신규(A-07 매출 현황에 예약액, 해제액, 조정액 표시 필요)
-- ----------------------------------------------------------------------------
CREATE TABLE daily_settlement_analytics (
    business_date             DATE    NOT NULL,
    charge_amount    BIGINT  NOT NULL DEFAULT 0,
    deduct_amount    BIGINT  NOT NULL DEFAULT 0,
    reserved_amount  BIGINT  NOT NULL DEFAULT 0  COMMENT '[2026-10-01 신규] 그날 예약액(RESERVE 합)',
    released_amount  BIGINT  NOT NULL DEFAULT 0  COMMENT '[2026-10-01 신규] 그날 예약 중 쓰지 않아 해제한 금액(RELEASE 합)',
    refund_amount    BIGINT  NOT NULL DEFAULT 0,
    adjust_amount    BIGINT  NOT NULL DEFAULT 0  COMMENT '[2026-10-01 신규] 관리자 조정액(ADJUST 합, ±). CHECK에서 제외(음수 허용)',
    unspent_balance  BIGINT  NOT NULL DEFAULT 0  COMMENT '미소진 잔액 합계 = 점주 잔액 합 + 예약 중 포인트(그날 예약액 − 당일 소진)',
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,

    PRIMARY KEY (business_date),

    CONSTRAINT chk_daily_settlement_analytics_nonneg CHECK (charge_amount >= 0 AND deduct_amount >= 0 AND reserved_amount >= 0 AND released_amount >= 0 AND refund_amount >= 0 AND unspent_balance >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='일별 정산 지표. [2026-10-01] reserved_amount/released_amount/adjust_amount 신규(A-07 매출 현황에 예약액, 해제액, 조정액 표시 필요)';

-- ----------------------------------------------------------------------------
-- daily_store_analytics  |  일별 가게/캠페인 지표
-- ----------------------------------------------------------------------------
CREATE TABLE daily_store_analytics (
    business_date                    DATE  NOT NULL,
    new_store_count         INT   NOT NULL DEFAULT 0,
    cumulative_store_count  INT   NOT NULL DEFAULT 0,
    active_campaign_count   INT   NOT NULL DEFAULT 0,
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NOT NULL,

    PRIMARY KEY (business_date),

    CONSTRAINT chk_daily_store_analytics_nonneg CHECK (new_store_count >= 0 AND cumulative_store_count >= 0 AND active_campaign_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='일별 가게/캠페인 지표';

-- ----------------------------------------------------------------------------
-- event_log  |  분석 도메인 source of truth, append-only. 13개월 보존(파티셔닝 없이 야간 배치 DELETE)
--   소프트 참조: member_id -> member(회원), campaign_id -> campaign(캠페인), store_id -> store(가게), serve_id -> serve_log(광고 서빙)
-- ----------------------------------------------------------------------------
CREATE TABLE event_log (
    event_log_id  BIGINT       NOT NULL AUTO_INCREMENT,
    event_id      VARCHAR(64)  NOT NULL                 COMMENT '중복 방지용 멱등키',
    event_type    VARCHAR(30)  NOT NULL,
    member_id     BIGINT       NOT NULL                 COMMENT '소프트 참조',
    campaign_id   BIGINT       NOT NULL                 COMMENT '소프트 참조',
    store_id      BIGINT       NOT NULL                 COMMENT '소프트 참조',
    serve_id      BINARY(16)                            COMMENT '[2026-10-01 변경] BIGINT -> BINARY(16). point_ledger.serve_id와 같은 사유다. 광고서빙(박준서) 실제 구현이 UUID(BINARY(16))',
    wish_id       BIGINT,
    issue_id      BIGINT,
    occurred_at   DATETIME(6)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,

    PRIMARY KEY (event_log_id),
    UNIQUE KEY uk_event_log_event_id (event_id),
    KEY idx_event_log_member_type_occurred (member_id, event_type, occurred_at),
    KEY idx_event_log_occurred (occurred_at),

    CONSTRAINT chk_event_log_event_type CHECK (event_type IN ('impression', 'wish', 'pass', 'issue_open', 'issue', 'redeem', 'expire', 'map_view', 'wishlist_view', 'notification_sent', 'poster_open')),
    CONSTRAINT chk_event_log_issue_id CHECK ((event_type IN ('issue_open', 'issue') AND issue_id IS NOT NULL) OR (event_type NOT IN ('issue_open', 'issue') AND issue_id IS NULL)),
    CONSTRAINT chk_event_log_wish_id CHECK ((event_type = 'wish' AND wish_id IS NOT NULL) OR event_type = 'wishlist_view' OR (event_type NOT IN ('wish', 'wishlist_view') AND wish_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='분석 도메인 source of truth, append-only. 13개월 보존(파티셔닝 없이 야간 배치 DELETE)';

-- ----------------------------------------------------------------------------
-- campaign_daily_report  |  캠페인 일별 리포트(60행 광고주 노출 리포트의 원천). [2026-10-04] 캠페인 도메인에서 분석 도메인으로 이동. 분석의 일별 집계 배치가 채운다
--   소프트 참조: campaign_id -> campaign(캠페인)
-- ----------------------------------------------------------------------------
CREATE TABLE campaign_daily_report (
    campaign_daily_report_id   BIGINT        NOT NULL AUTO_INCREMENT         COMMENT '캠페인 일별 집계 PK',
    campaign_id                BIGINT        NOT NULL                        COMMENT '캠페인 ID. 소프트 참조(캠페인 도메인)',
    business_date                DATE          NOT NULL                        COMMENT '집계 날짜',
    daily_budget        BIGINT        NOT NULL DEFAULT 0              COMMENT '해당 날짜의 하루 예산',
    charged_amount             BIGINT        NOT NULL DEFAULT 0              COMMENT '유효 노출로 실제 차감된 포인트',
    refunded_amount            BIGINT        NOT NULL DEFAULT 0              COMMENT '무효 처리로 환급된 포인트',
    spent_amount               BIGINT        NOT NULL DEFAULT 0              COMMENT '실제 소진 포인트',
    budget_spend_rate          DECIMAL(9,6)  NOT NULL DEFAULT '0.000000'     COMMENT '예산 소진율',
    impression_unit_cost       BIGINT        NOT NULL DEFAULT 0              COMMENT '적용된 노출 단가',
    valid_impression_count     BIGINT        NOT NULL DEFAULT 0              COMMENT '유효 노출 수',
    billable_impression_count  BIGINT        NOT NULL DEFAULT 0              COMMENT '과금된 유효 노출 수',
    not_billed_impression_count  BIGINT        NOT NULL DEFAULT 0              COMMENT '미과금 유효 노출 수',
    invalid_impression_count   BIGINT        NOT NULL DEFAULT 0              COMMENT '무효 노출 수',
    wish_count                 BIGINT        NOT NULL DEFAULT 0              COMMENT '찜 수',
    issued_count                BIGINT        NOT NULL DEFAULT 0              COMMENT '쿠폰 발급 수',
    redeem_count               BIGINT        NOT NULL DEFAULT 0              COMMENT '쿠폰 사용 수',
    report_status              VARCHAR(20)   NOT NULL DEFAULT 'AGGREGATING'  COMMENT '리포트 상태(AGGREGATING, FINALIZED)',
    finalized_at               DATETIME(6)                                   COMMENT '일별 리포트 확정 시각',
    created_at                 DATETIME(6)   NOT NULL                        COMMENT '생성일시',
    updated_at                 DATETIME(6)   NOT NULL                        COMMENT '수정일시',

    PRIMARY KEY (campaign_daily_report_id),
    UNIQUE KEY uk_campaign_daily_report_campaign_id_business_date (campaign_id, business_date),

    CONSTRAINT chk_campaign_daily_report_impression CHECK (valid_impression_count = (billable_impression_count + not_billed_impression_count)),
    CONSTRAINT chk_campaign_daily_report_status CHECK ((report_status = 'AGGREGATING' AND finalized_at IS NULL) OR (report_status = 'FINALIZED' AND finalized_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- campaign_daily_impression_reason_report
-- ----------------------------------------------------------------------------
CREATE TABLE campaign_daily_impression_reason_report (
    campaign_daily_impression_reason_report_id  BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '캠페인 일별 노출 사유 집계 PK',
    campaign_daily_report_id                    BIGINT       NOT NULL                 COMMENT '캠페인 일별 리포트 ID',
    impression_type                             VARCHAR(20)  NOT NULL                 COMMENT '노출 구분(UNBILLED, INVALID)',
    reason_code                                 VARCHAR(50)  NOT NULL                 COMMENT '미과금 사유(NOT_BILLED_BUDGET, NOT_BILLED_PAUSED, NOT_BILLED_SOLD_OUT) 또는 무효 사유(EXPIRED_OR_UNKNOWN, NOT_OWNER)',
    impression_count                            BIGINT       NOT NULL DEFAULT 0       COMMENT '해당 사유의 노출 수',
    created_at                                  DATETIME(6)  NOT NULL                 COMMENT '생성일시',
    updated_at                                  DATETIME(6)  NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (campaign_daily_impression_reason_report_id),
    UNIQUE KEY uk_campaign_impression_reason_report_day_type_reason (campaign_daily_report_id, impression_type, reason_code),

    CONSTRAINT fk_campaign_daily_impression_reason_report_campaign_daily_report FOREIGN KEY (campaign_daily_report_id) REFERENCES campaign_daily_report (campaign_daily_report_id),

    CONSTRAINT chk_campaign_daily_impression_reason CHECK ((impression_type = 'UNBILLED' AND reason_code IN ('NOT_BILLED_BUDGET', 'NOT_BILLED_PAUSED', 'NOT_BILLED_SOLD_OUT')) OR (impression_type = 'INVALID' AND reason_code IN ('EXPIRED_OR_UNKNOWN', 'NOT_OWNER')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- campaign_daily_report_history
-- ----------------------------------------------------------------------------
CREATE TABLE campaign_daily_report_history (
    campaign_daily_report_history_id  BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '캠페인 일별 리포트 변경 이력 ID',
    campaign_daily_report_id          BIGINT        NOT NULL                 COMMENT '캠페인 일별 리포트 ID',
    revision_no                       INT           NOT NULL                 COMMENT '변경 차수',
    change_reason                    VARCHAR(255)                           COMMENT '변경 사유',
    before_data                       JSON          NOT NULL                 COMMENT '변경 전 데이터',
    after_data                        JSON          NOT NULL                 COMMENT '변경 후 데이터',
    created_at                        DATETIME(6)   NOT NULL                 COMMENT '생성일시',
    updated_at                        DATETIME(6)   NOT NULL,

    PRIMARY KEY (campaign_daily_report_history_id),
    KEY fk_campaign_daily_report_history_campaign_daily_report (campaign_daily_report_id),

    CONSTRAINT fk_campaign_daily_report_history_campaign_daily_report FOREIGN KEY (campaign_daily_report_id) REFERENCES campaign_daily_report (campaign_daily_report_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- campaign_daily_slot_report
-- ----------------------------------------------------------------------------
CREATE TABLE campaign_daily_slot_report (
    campaign_daily_slot_report_id  BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '캠페인 일별 슬롯 집계 PK',
    campaign_daily_report_id       BIGINT       NOT NULL                 COMMENT '캠페인 일별 리포트 ID',
    slot_type                      VARCHAR(20)  NOT NULL                 COMMENT '슬롯 유형(ALLOCATION, RELEVANCE)',
    impression_count               BIGINT       NOT NULL DEFAULT 0       COMMENT '해당 슬롯 유형의 노출 수',
    created_at                     DATETIME(6)  NOT NULL                 COMMENT '생성일시',
    updated_at                     DATETIME(6)  NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (campaign_daily_slot_report_id),
    UNIQUE KEY uk_campaign_daily_slot_report_campaign_daily_report_id_slot_type (campaign_daily_report_id, slot_type),

    CONSTRAINT fk_campaign_daily_slot_report_campaign_daily_report FOREIGN KEY (campaign_daily_report_id) REFERENCES campaign_daily_report (campaign_daily_report_id),

    CONSTRAINT chk_campaign_daily_slot_type CHECK (slot_type IN ('ALLOCATION', 'RELEVANCE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- campaign_hourly_report
-- ----------------------------------------------------------------------------
CREATE TABLE campaign_hourly_report (
    campaign_hourly_report_id  BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '캠페인 시간대별 리포트 PK',
    campaign_daily_report_id   BIGINT       NOT NULL                 COMMENT '캠페인 일별 리포트 ID',
    report_hour                TINYINT      NOT NULL                 COMMENT '집계 시간(10, 11, 12)',
    impression_count           BIGINT       NOT NULL DEFAULT 0       COMMENT '해당 시간대 노출 수',
    created_at                 DATETIME(6)  NOT NULL                 COMMENT '생성일시',
    updated_at                 DATETIME(6)  NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (campaign_hourly_report_id),
    UNIQUE KEY uk_campaign_hourly_report_campaign_daily_report_id_report_hour (campaign_daily_report_id, report_hour),

    CONSTRAINT fk_campaign_hourly_report_campaign_daily_report FOREIGN KEY (campaign_daily_report_id) REFERENCES campaign_daily_report (campaign_daily_report_id),

    CONSTRAINT chk_campaign_hourly_report_hour CHECK (report_hour IN (10, 11, 12))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
