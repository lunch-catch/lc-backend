CREATE TABLE pg_charge_reconciliation_pending (
    pg_charge_reconciliation_pending_id BIGINT NOT NULL AUTO_INCREMENT,
    payment_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    last_error VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (pg_charge_reconciliation_pending_id),
    CONSTRAINT uk_pg_charge_reconciliation_pending_payment UNIQUE (payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
