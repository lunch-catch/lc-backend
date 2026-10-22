-- 퍼널(A-03)의 일별 지표를 담는 전용 표를 추가한다

CREATE TABLE daily_funnel_analytics (
    business_date            DATE         NOT NULL,
    valid_impression_count   BIGINT       NOT NULL DEFAULT 0 COMMENT '유효 노출 수. 쿠폰 소진 뒤 노출을 포함한다',
    invalid_impression_count BIGINT       NOT NULL DEFAULT 0 COMMENT '무효 노출 수. EXPIRED_OR_UNKNOWN과 NOT_OWNER의 합',
    wish_count               BIGINT       NOT NULL DEFAULT 0 COMMENT '피드 카드 찜 수. event_log의 wish 행 수',
    issue_count              BIGINT       NOT NULL DEFAULT 0 COMMENT '쿠폰 발급 수. 쿠폰 집계 조회가 원천',
    redeem_count             BIGINT       NOT NULL DEFAULT 0 COMMENT '쿠폰 사용 수. 쿠폰 집계 조회가 원천',
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,

    PRIMARY KEY (business_date),

    CONSTRAINT chk_daily_funnel_analytics_nonneg CHECK (
        valid_impression_count >= 0
        AND invalid_impression_count >= 0
        AND wish_count >= 0
        AND issue_count >= 0
        AND redeem_count >= 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='일별 퍼널 지표. 플랫폼 전체 하루 한 행';
