-- point_ledger  |  충전 직후 재예약이 한 결제로 캠페인 여럿을 재예약할 수 있게 멱등 키를 나눈다 (2026-10-16, #319)
--   uk_point_ledger_payment_entry (payment_id, entry_type) 는 지금까지 CHARGE 전용이었는데, 재예약 RESERVE 도
--   payment_id 를 가지면서 같은 결제로 캠페인 하나만 재예약하도록 막는 부작용이 생겼다.
--   생성 컬럼으로 키를 나눈다. payment_entry_dedup_key 는 RESERVE 를 빼서 CHARGE 전용으로 남기고,
--   recharge_reserve_dedup_key 는 결제×캠페인으로 재예약 RESERVE 만 막는다. 정기(00:00) RESERVE 는
--   payment_id 가 없어 둘 다 NULL 이라 기존 day_reserve_dedup_key 만 그대로 적용된다.
--   fk_point_ledger_payment 가 payment_id 선두 인덱스를 요구해 그 인덱스를 먼저 두고서야 기존
--   uk_point_ledger_payment_entry 를 지울 수 있다. 한 ALTER 에서 지우고 더하면 그 사이 인덱스가 없어진다.
ALTER TABLE point_ledger
    ADD INDEX idx_point_ledger_payment_id (payment_id);

ALTER TABLE point_ledger DROP INDEX uk_point_ledger_payment_entry;

ALTER TABLE point_ledger
    ADD COLUMN payment_entry_dedup_key VARCHAR(50)
        GENERATED ALWAYS AS (
            CASE WHEN payment_id IS NOT NULL AND entry_type <> 'RESERVE'
                 THEN CONCAT(payment_id, '|', entry_type)
                 ELSE NULL END
        ) STORED                                                            COMMENT 'CHARGE 등 결제당 한 줄만 허용하는 종류의 멱등 키. RESERVE 는 아래 키로 따로 막는다',
    ADD COLUMN recharge_reserve_dedup_key VARCHAR(50)
        GENERATED ALWAYS AS (
            CASE WHEN entry_type = 'RESERVE' AND payment_id IS NOT NULL
                 THEN CONCAT(payment_id, '|', campaign_id)
                 ELSE NULL END
        ) STORED                                                            COMMENT '충전 직후 재예약의 결제×캠페인 멱등 키. 같은 결제로 캠페인마다 한 번만 재예약한다';

ALTER TABLE point_ledger
    ADD UNIQUE KEY uk_point_ledger_payment_entry (payment_entry_dedup_key),
    ADD UNIQUE KEY uk_point_ledger_recharge_reserve_dedup (recharge_reserve_dedup_key);
