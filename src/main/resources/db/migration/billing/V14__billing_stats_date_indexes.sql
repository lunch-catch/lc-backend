-- ----------------------------------------------------------------------------
-- deduction_result, point_ledger  |  분석 집계가 영업일로 읽는 조회가 그날 행만 읽게 한다 (#1241)
--   분석의 일별 집계(BillingStatsQueryService)는 영업일 하나로 판정 수와 예약, 해제 합계를 묶어 읽는다
--   판정의 idx_deduction_result_campaign_date 와 원장의 idx_point_ledger_owner_business_date 는 앞 열이
--   캠페인과 점주라 영업일만으로는 타지 못하고, 보관된 판정과 원장 전체를 읽었다. 그래서 그 시간이
--   그날이 아니라 쌓인 기간에 비례해 늘었다
--   판정 3일치 290만 행, 원장 262만 행에서 판정 수가 2.8초에서 0.4 ~ 0.8초, 예약과 해제 합계가 1.5초에서 0.13초가 됐다
--   ALGORITHM = INPLACE, LOCK = NONE 으로 만드는 동안에도 노출 차감의 쓰기를 막지 않는다
-- ----------------------------------------------------------------------------

/*
 * 판정은 노출마다 쓰여 인덱스 하나만큼 쓰기가 는다.
 * 인덱스가 3개인 테이블이라 원장(12개)보다 부담이 작다.
 */
ALTER TABLE deduction_result
    ADD INDEX idx_deduction_result_date (business_date, campaign_id, billing_status),
    ALGORITHM = INPLACE, LOCK = NONE;

/*
 * 원장은 인덱스를 늘리지 않고 바꿔 끼운다. 노출마다 DEDUCT 를 쓰는 테이블이라 인덱스 하나가 차감 쓰기 비용이다.
 * (owner_id, business_date) 를 쓰던 점주 조회는 모두 entry_type 을 함께 걸어 idx_point_ledger_owner_type_created 가 받는다.
 * 같은 데이터에서 점주 조회 여섯의 실행 계획과 시간(0.12초 안팎)이 바꾸기 전과 같았다.
 */
ALTER TABLE point_ledger
    DROP INDEX idx_point_ledger_owner_business_date,
    ADD INDEX idx_point_ledger_date_type (business_date, entry_type),
    ALGORITHM = INPLACE, LOCK = NONE;
