-- ----------------------------------------------------------------------------
-- deduction_result  |  소진을 원장 DEDUCT 대신 판정에서 읽는 조회가 판정 표를 훑지 않게 한다 (#1291, #1287)
--   판정의 BILLED 단가 합이 소진의 정본이 되어 원장 DEDUCT 를 읽던 조회가 판정을 읽는다
--   기존 인덱스는 캠페인과 영업일이 앞이라 점주나 기록 시각으로 거르는 조회가 타지 못한다
--   unit_price 를 끝에 두어 합계를 인덱스만으로 구한다
--   묶음 차감 부하 시험의 B 회차가 이 두 인덱스를 붙인 조건이다(docs/design/billing/loadtest/묶음_차감_부하_시험_결과.md)
--   ALGORITHM = INPLACE, LOCK = NONE 으로 만드는 동안에도 노출 차감의 쓰기를 막지 않는다
-- ----------------------------------------------------------------------------

-- 점주가 앞인 인덱스는 점주 잔액 화면의 예약 중 포인트와 관리자 기간별 합계가, 기록 시각이 앞인 인덱스는 점주의 캠페인별 소진과 분석의 미소진 변동이 쓴다.
ALTER TABLE deduction_result
    ADD INDEX idx_deduction_result_owner_date (owner_id, business_date, billing_status, unit_price),
    ADD INDEX idx_deduction_result_created (created_at, billing_status, unit_price),
    ALGORITHM = INPLACE, LOCK = NONE;
