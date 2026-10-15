-- ----------------------------------------------------------------------------
-- settlement_mismatch  |  예약액 초과 소진 대사 종류 추가 (2026-10-15, #306)
--   CAMPAIGN_OVERSPEND 는 캠페인의 그날 원장 DEDUCT 합이 그날 예약액(budget_daily_plan.daily_budget)을 넘은 건이다.
--   expected_value 는 예약액, actual_value 는 원장 DEDUCT 합이다. 대상 ID 는 캠페인 ID 다.
--   CAMPAIGN_SPEND_VS_LEDGER(카운터 대 원장)와 따로 둔다. 해시 재구성으로 카운터가 원장과 같아지면 그 대조로는
--   초과 소진(알려진 한계 L-2, L-4)이 보이지 않고, 한 종류에 두 의미를 담으면 expected/actual 의 뜻이 섞인다.
--   같은 이름의 CHECK 는 한 ALTER 에서 DROP 과 ADD 를 함께 하지 못해 문장을 나눈다(V2 와 같다).
-- ----------------------------------------------------------------------------
ALTER TABLE settlement_mismatch DROP CHECK chk_settlement_mismatch_check_type;

ALTER TABLE settlement_mismatch
    ADD CONSTRAINT chk_settlement_mismatch_check_type
        CHECK (check_type IN ('PG_VS_CHARGE', 'CAMPAIGN_SPEND_VS_LEDGER', 'CAMPAIGN_OVERSPEND'));
