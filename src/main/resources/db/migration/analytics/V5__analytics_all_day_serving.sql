-- ----------------------------------------------------------------------------
-- 하루 종일 서빙에 맞춘 분석 제약 (2026-10-19, #497)
--   광고 서빙이 서빙 시간대(10:00~12:59) 없이 하루 종일 카드를 내려준다. 그래서 두 가지가 바뀐다.
--   V4 는 퍼널 표(#461)가 쓴다. 같은 이름의 CHECK 는 한 ALTER 에서 DROP 과 ADD 를 함께 하지 못해 문장을 나눈다.
-- ----------------------------------------------------------------------------

-- ----------------------------------------------------------------------------
-- campaign_hourly_report  |  시간대를 0~23시로 넓힌다
--   제약을 서빙 시간 설정과 맞물리지 않고 하루 전체로 푼다. 서빙 시간을 다시 바꿔도 이 제약은 다시 바꾸지 않는다.
-- ----------------------------------------------------------------------------
ALTER TABLE campaign_hourly_report DROP CHECK chk_campaign_hourly_report_hour;

ALTER TABLE campaign_hourly_report
    ADD CONSTRAINT chk_campaign_hourly_report_hour CHECK (report_hour BETWEEN 0 AND 23);

ALTER TABLE campaign_hourly_report
    MODIFY COLUMN report_hour TINYINT NOT NULL COMMENT '집계 시간(0~23)';

-- ----------------------------------------------------------------------------
-- daily_campaign_not_billed_analytics, campaign_daily_impression_reason_report  |  미과금 사유 NOT_BILLED_NO_PLAN 추가
--   정산이 노출을 받은 날로 차감하면서 그날 계획이 없는 캠페인의 노출을 NOT_BILLED_NO_PLAN 으로 남긴다(billing V8).
--   00:00 예약 전이거나 전날 집행이 끝난 캠페인이다. 이 사유를 받아야 유효 노출 = 과금 + 미과금이 맞는다.
-- ----------------------------------------------------------------------------
ALTER TABLE daily_campaign_not_billed_analytics DROP CHECK chk_daily_campaign_not_billed_reason;

ALTER TABLE daily_campaign_not_billed_analytics
    ADD CONSTRAINT chk_daily_campaign_not_billed_reason
        CHECK (not_billed_reason IN ('NOT_BILLED_BUDGET', 'NOT_BILLED_PAUSED', 'NOT_BILLED_NO_PLAN', 'NOT_BILLED_SOLD_OUT'));

ALTER TABLE campaign_daily_impression_reason_report DROP CHECK chk_campaign_daily_impression_reason;

ALTER TABLE campaign_daily_impression_reason_report
    ADD CONSTRAINT chk_campaign_daily_impression_reason
        CHECK ((impression_type = 'UNBILLED' AND reason_code IN ('NOT_BILLED_BUDGET', 'NOT_BILLED_PAUSED', 'NOT_BILLED_NO_PLAN', 'NOT_BILLED_SOLD_OUT'))
            OR (impression_type = 'INVALID' AND reason_code IN ('EXPIRED_OR_UNKNOWN', 'NOT_OWNER')));

ALTER TABLE campaign_daily_impression_reason_report
    MODIFY COLUMN reason_code VARCHAR(50) NOT NULL COMMENT '미과금 사유(NOT_BILLED_BUDGET, NOT_BILLED_PAUSED, NOT_BILLED_NO_PLAN, NOT_BILLED_SOLD_OUT) 또는 무효 사유(EXPIRED_OR_UNKNOWN, NOT_OWNER)';
