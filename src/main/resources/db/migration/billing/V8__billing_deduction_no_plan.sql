-- ----------------------------------------------------------------------------
-- deduction_result  |  계획 없음 판정 추가 (2026-10-19, #497)
--   서빙이 하루 종일이 되면서 노출은 받은 날의 예산으로 차감한다. 그날 계획 행이 없는 캠페인의 노출은
--   NOT_BILLED_NO_PLAN 으로 남긴다. 00:00 예약이 그 캠페인의 계획을 만들기 전이거나 전날 집행이 끝난 캠페인이다.
--   행을 남겨야 광고 서빙의 유효 노출 수가 정산 판정 합(BILLED + NOT_BILLED_*)에 쿠폰 소진 뒤 노출을 더한 값과 같다.
--   계획이 없으므로 unit_price 는 그때의 운영 단가(platform_setting)를 남긴다. 과금하지 않아 금액 계산에는 쓰지 않는다.
--   같은 이름의 CHECK 는 한 ALTER 에서 DROP 과 ADD 를 함께 하지 못해 문장을 나눈다(V2 와 같다).
-- ----------------------------------------------------------------------------
ALTER TABLE deduction_result DROP CHECK chk_deduction_result_status;

ALTER TABLE deduction_result
    ADD CONSTRAINT chk_deduction_result_status
        CHECK (billing_status IN ('BILLED', 'NOT_BILLED_BUDGET', 'NOT_BILLED_PAUSED', 'NOT_BILLED_NO_PLAN'));

ALTER TABLE deduction_result
    MODIFY COLUMN billing_status VARCHAR(20) NOT NULL COMMENT 'BILLED/NOT_BILLED_BUDGET/NOT_BILLED_PAUSED/NOT_BILLED_NO_PLAN';
