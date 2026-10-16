-- analytics 구현의 공통 저장 구조를 추가한다

ALTER TABLE event_log
    DROP CHECK chk_event_log_issue_id,
    MODIFY member_id BIGINT NULL COMMENT '소프트 참조. issue_open은 회원이 없다',
    MODIFY campaign_id BIGINT NULL COMMENT '소프트 참조. 화면 조회 이벤트는 대상 캠페인이 없다',
    MODIFY store_id BIGINT NULL COMMENT '소프트 참조. 화면 조회 이벤트는 대상 가게가 없다',
    DROP COLUMN issue_id,
    ADD COLUMN coupon_event_id BIGINT NULL AFTER wish_id,
    ADD COLUMN member_coupon_id BIGINT NULL AFTER coupon_event_id,
    ADD CONSTRAINT chk_event_log_member_id CHECK (
        (event_type = 'issue_open' AND member_id IS NULL)
        OR (event_type <> 'issue_open' AND member_id IS NOT NULL)
    ),
    ADD CONSTRAINT chk_event_log_target_ids CHECK (
        (campaign_id IS NULL AND store_id IS NULL
            AND event_type IN ('map_view', 'wishlist_view', 'issue_open'))
        OR (campaign_id IS NOT NULL AND store_id IS NOT NULL
            AND event_type NOT IN ('map_view', 'wishlist_view'))
    ),
    ADD CONSTRAINT chk_event_log_coupon_ids CHECK (
        (event_type = 'issue_open' AND coupon_event_id IS NOT NULL AND member_coupon_id IS NULL)
        OR (event_type = 'issue' AND coupon_event_id IS NOT NULL AND member_coupon_id IS NOT NULL)
        OR (event_type IN ('redeem', 'expire') AND member_coupon_id IS NOT NULL)
        OR (event_type NOT IN ('issue_open', 'issue', 'redeem', 'expire')
            AND coupon_event_id IS NULL AND member_coupon_id IS NULL)
    );

ALTER TABLE daily_settlement_analytics
    ADD COLUMN positive_adjust_amount BIGINT NOT NULL DEFAULT 0
        COMMENT '과오 차감 정정에 쓰는 양수 ADJUST 합' AFTER adjust_amount,
    ADD CONSTRAINT chk_daily_settlement_positive_adjust_nonneg
        CHECK (positive_adjust_amount >= 0);

ALTER TABLE daily_owner_spend
    DROP CHECK chk_daily_owner_spend_nonneg,
    MODIFY spent_amount BIGINT NOT NULL DEFAULT 0
        COMMENT 'DEDUCT 합에서 양수 ADJUST 합을 뺀 금액';

ALTER TABLE daily_segment_analytics
    DROP CHECK chk_daily_segment_analytics_nonneg,
    ADD CONSTRAINT chk_daily_segment_analytics_nonneg CHECK (
        impression_count >= 0
        AND wish_count >= 0
        AND issued_count >= 0
        AND redeem_count >= 0
    );

ALTER TABLE campaign_daily_report
    ADD COLUMN owner_id BIGINT NOT NULL COMMENT '점주 ID. 소프트 참조' AFTER campaign_id,
    ADD COLUMN store_id BIGINT NOT NULL COMMENT '가게 ID. 소프트 참조' AFTER owner_id,
    MODIFY refunded_amount BIGINT NOT NULL DEFAULT 0
        COMMENT '캠페인에 연결된 과오 차감 정정용 양수 ADJUST 합',
    ADD KEY idx_campaign_daily_report_owner_business_date (owner_id, business_date);

CREATE TABLE daily_campaign_fraud_analytics (
    business_date             DATE         NOT NULL,
    campaign_id              BIGINT       NOT NULL COMMENT '소프트 참조',
    valid_count              BIGINT       NOT NULL DEFAULT 0,
    invalid_count            BIGINT       NOT NULL DEFAULT 0,
    distinct_member_count    BIGINT       NOT NULL DEFAULT 0,
    top_members_count        BIGINT       NOT NULL DEFAULT 0 COMMENT '상위 5개 회원의 유효 노출 수',
    concentration_suspected  BOOLEAN      NOT NULL DEFAULT 0,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,

    PRIMARY KEY (business_date, campaign_id),

    CONSTRAINT chk_daily_campaign_fraud_nonneg CHECK (
        valid_count >= 0
        AND invalid_count >= 0
        AND distinct_member_count >= 0
        AND top_members_count >= 0
    ),
    CONSTRAINT chk_daily_campaign_fraud_top_members CHECK (top_members_count <= valid_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='캠페인과 영업일별 무효 비율과 회원 집중 경고 원천';

CREATE TABLE daily_member_usage (
    business_date  DATE         NOT NULL,
    member_id      BIGINT       NOT NULL COMMENT '소프트 참조',
    wish_count     BIGINT       NOT NULL DEFAULT 0,
    redeem_count   BIGINT       NOT NULL DEFAULT 0,
    saved_amount   BIGINT       NOT NULL DEFAULT 0,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,

    PRIMARY KEY (business_date, member_id),
    KEY idx_daily_member_usage_member_id (member_id),

    CONSTRAINT chk_daily_member_usage_nonneg CHECK (
        wish_count >= 0 AND redeem_count >= 0 AND saved_amount >= 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='회원과 영업일별 누적 사용 통계 원천';

CREATE TABLE aggregation_pending_date (
    business_date  DATE         NOT NULL,
    reason         VARCHAR(30)  NOT NULL,
    recorded_at    DATETIME(6)  NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,

    PRIMARY KEY (business_date),

    CONSTRAINT chk_aggregation_pending_date_reason CHECK (
        reason IN ('FAILED', 'SOURCE_NOT_READY', 'RERUN_UNAVAILABLE', 'ADJUSTED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='성공할 때까지 다시 처리할 영업일';
