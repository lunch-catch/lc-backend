-- ----------------------------------------------------------------------------
-- budget_daily_plan  |  그 계획의 예약을 해제한 시각 (#1049)
--   노출 차감은 이 값으로 마감을 판정한다. 해제 전에 도착한 노출은 날짜가 지났어도 과금하고, 해제 뒤 도착한 노출은 NOT_BILLED_CLOSED 다
--   해제는 남은 금액과 관계없이 적는다. 남은 금액이 0 이면 RELEASE 원장이 없어 원장으로는 해제 여부를 알 수 없다
--   열은 끝에 더해 테이블을 복사하지 않는다(INSTANT)
-- ----------------------------------------------------------------------------
ALTER TABLE budget_daily_plan
    ADD COLUMN released_at DATETIME(6) COMMENT '그 계획의 예약을 해제한 시각. NULL 이면 해제 전이라 노출을 과금한다',
    ALGORITHM = INSTANT;

-- 해제가 이미 끝난 계획만 그 해제의 시각으로 채운다. RELEASE 원장이 있으면 해제가 끝난 것이다
--   해제 전이거나 해제가 실패한 계획은 비워 둔다. 채우면 릴레이가 그 뒤 넘기는 노출이 마감 미과금이 된다
--   남은 금액이 0 이라 RELEASE 가 없는 계획도 비워 둔다. 예약을 다 써서 더 오는 노출은 예산 미과금이다
--   어제보다 앞선 계획도 채워지지만 차감은 그 날을 날짜로 마감으로 본다. 날짜 함수를 쓰지 않으려고 날짜로 거르지 않는다
UPDATE budget_daily_plan p
  JOIN point_ledger l
    ON l.campaign_id = p.campaign_id
   AND l.business_date = p.business_date
   AND l.entry_type = 'RELEASE'
   SET p.released_at = l.created_at
 WHERE p.released_at IS NULL;
