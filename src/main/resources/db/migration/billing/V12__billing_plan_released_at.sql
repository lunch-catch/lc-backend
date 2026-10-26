-- ----------------------------------------------------------------------------
-- budget_daily_plan  |  그 계획의 예약을 해제한 시각 (#1049)
--   노출 차감은 이 값으로 마감을 판정한다. 해제 전에 도착한 노출은 날짜가 지났어도 과금하고, 해제 뒤 도착한 노출은 NOT_BILLED_CLOSED 다
--   해제는 남은 금액과 관계없이 적는다. 남은 금액이 0 이면 RELEASE 원장이 없어 원장으로는 해제 여부를 알 수 없다
--   열은 끝에 더해 테이블을 복사하지 않는다(INSTANT)
-- ----------------------------------------------------------------------------
ALTER TABLE budget_daily_plan
    ADD COLUMN released_at DATETIME(6) COMMENT '그 계획의 예약을 해제한 시각. NULL 이면 해제 전이라 노출을 과금한다',
    ALGORITHM = INSTANT;

-- 어제 계획은 00:00 해제가 끝난 것으로 채운다. 어제보다 앞선 계획은 차감이 날짜로 마감을 보므로 채우지 않는다
-- 행 수는 하루 계획 수(캠페인 수)라 짧게 끝난다. 배포가 그날 해제보다 먼저면 그 사이 어제 노출은 마감 미과금이 된다
UPDATE budget_daily_plan
   SET released_at = CURRENT_TIMESTAMP(6)
 WHERE business_date = CURRENT_DATE - INTERVAL 1 DAY
   AND released_at IS NULL;
