/*
 * daily_campaign_not_billed_analytics, campaign_daily_impression_reason_report  |  미과금 사유 NOT_BILLED_CLOSED 추가 (#1014)
 * 정산은 노출 영업일의 다음 날 00:00 뒤에 처음 처리하는 노출을 과금하지 않고 NOT_BILLED_CLOSED 로 남긴다(billing V10).
 * 광고 서빙의 유효 노출에는 들어 있으므로 이 사유를 받아야 유효 노출 = 과금 + 미과금이 맞는다.
 * 같은 이름의 CHECK 는 한 ALTER 에서 DROP 과 ADD 를 함께 하지 못해 문장을 나눈다(V5 와 같다).
 */
ALTER TABLE daily_campaign_not_billed_analytics DROP CHECK chk_daily_campaign_not_billed_reason;

ALTER TABLE daily_campaign_not_billed_analytics
    ADD CONSTRAINT chk_daily_campaign_not_billed_reason
        CHECK (not_billed_reason IN ('NOT_BILLED_BUDGET', 'NOT_BILLED_PAUSED', 'NOT_BILLED_NO_PLAN', 'NOT_BILLED_CLOSED',
                                     'NOT_BILLED_SOLD_OUT'));

ALTER TABLE campaign_daily_impression_reason_report DROP CHECK chk_campaign_daily_impression_reason;

ALTER TABLE campaign_daily_impression_reason_report
    ADD CONSTRAINT chk_campaign_daily_impression_reason
        CHECK ((impression_type = 'UNBILLED' AND reason_code IN ('NOT_BILLED_BUDGET', 'NOT_BILLED_PAUSED', 'NOT_BILLED_NO_PLAN',
                                                                 'NOT_BILLED_CLOSED', 'NOT_BILLED_SOLD_OUT'))
            OR (impression_type = 'INVALID' AND reason_code IN ('EXPIRED_OR_UNKNOWN', 'NOT_OWNER')));

ALTER TABLE campaign_daily_impression_reason_report
    MODIFY COLUMN reason_code VARCHAR(50) NOT NULL COMMENT '미과금 사유(NOT_BILLED_BUDGET, NOT_BILLED_PAUSED, NOT_BILLED_NO_PLAN, NOT_BILLED_CLOSED, NOT_BILLED_SOLD_OUT) 또는 무효 사유(EXPIRED_OR_UNKNOWN, NOT_OWNER)';
