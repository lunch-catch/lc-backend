/*
 * 기본 세계에서 기준일(D)의 집계 중(AGGREGATING) 캠페인 리포트를 자식 행과 함께 지운다 (#895).
 * 앱은 당일을 집계하지 않고 일별 배치는 FINALIZED 로만 쓴다. 그래서 앱이 만들지 않는 이 행이 점주 리포트에 0 으로 나갔다.
 * 이미 적용된 V7 은 체크섬 때문에 고치지 않는다. 그래서 그보다 뒤인 이 파일에서 지운다.
 * 기준일이 지나 배치가 그날을 FINALIZED 로 덮은 행은 남긴다.
 */

DELETE FROM campaign_daily_impression_reason_report
 WHERE campaign_daily_report_id IN (SELECT campaign_daily_report_id FROM campaign_daily_report
                                     WHERE campaign_id BETWEEN 10000001 AND 19999999 AND report_status = 'AGGREGATING');
DELETE FROM campaign_hourly_report
 WHERE campaign_daily_report_id IN (SELECT campaign_daily_report_id FROM campaign_daily_report
                                     WHERE campaign_id BETWEEN 10000001 AND 19999999 AND report_status = 'AGGREGATING');
DELETE FROM campaign_daily_slot_report
 WHERE campaign_daily_report_id IN (SELECT campaign_daily_report_id FROM campaign_daily_report
                                     WHERE campaign_id BETWEEN 10000001 AND 19999999 AND report_status = 'AGGREGATING');
DELETE FROM campaign_daily_report
 WHERE campaign_id BETWEEN 10000001 AND 19999999 AND report_status = 'AGGREGATING';
