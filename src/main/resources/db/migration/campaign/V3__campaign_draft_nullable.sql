-- 단계별로 저장 중인 DRAFT 캠페인이 아직 입력하지 않은 값을 비워 두거나 기본값으로 채울 수 있게 한다.
ALTER TABLE campaign
    MODIFY COLUMN target_age_groups VARCHAR(50) NOT NULL DEFAULT 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS' COMMENT 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS 중 다중 선택, 콤마 구분. 기본값은 전체',
    MODIFY COLUMN daily_budget      BIGINT      NULL COMMENT '포인트 단위',
    MODIFY COLUMN start_date        DATE        NULL,
    MODIFY COLUMN end_date          DATE        NULL;
