-- 영업일 마감 뒤 정산에 도착한 유효 노출을 미과금 판정으로 보존한다.
ALTER TABLE deduction_result DROP CHECK chk_deduction_result_status;

ALTER TABLE deduction_result
    ADD CONSTRAINT chk_deduction_result_status
        CHECK (billing_status IN ('BILLED', 'NOT_BILLED_BUDGET', 'NOT_BILLED_PAUSED',
                                  'NOT_BILLED_NO_PLAN', 'NOT_BILLED_CLOSED'));

ALTER TABLE deduction_result
    MODIFY COLUMN billing_status VARCHAR(20) NOT NULL
        COMMENT 'BILLED/NOT_BILLED_BUDGET/NOT_BILLED_PAUSED/NOT_BILLED_NO_PLAN/NOT_BILLED_CLOSED';
