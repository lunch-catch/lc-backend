-- ----------------------------------------------------------------------------
-- recharge_reservation_outbox  |  충전 직후 재예약(I-03)을 기다리는 행 (#1100)
--   새 CHARGE 를 만든 승인 트랜잭션에서 함께 넣는다. 재예약 워커가 집어 재예약하고, 실패하면 백오프로 다시 시도한다
--   business_date 는 충전한 영업일이다. 그날이 지나면 재예약하지 않고 EXPIRED 로 닫는다. 00:00 예약이 그 잔액을 배분한다
--   횟수 상한은 두지 않는다. 영업일이 행의 수명을 하루로 묶는다
-- ----------------------------------------------------------------------------
CREATE TABLE recharge_reservation_outbox (
    recharge_reservation_outbox_id  BIGINT        NOT NULL AUTO_INCREMENT,
    payment_id                      BIGINT        NOT NULL,
    business_date                   DATE          NOT NULL,
    status                          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempt_count                   INT           NOT NULL DEFAULT 0,
    next_retry_at                   DATETIME(6),
    last_error                      VARCHAR(500),
    created_at                      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                      DATETIME(6)   NOT NULL,

    PRIMARY KEY (recharge_reservation_outbox_id),
    UNIQUE KEY uk_recharge_reservation_outbox_payment (payment_id),
    KEY idx_recharge_reservation_outbox_retry (status, next_retry_at),

    CONSTRAINT fk_recharge_reservation_outbox_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),

    CONSTRAINT chk_recharge_reservation_outbox_next_retry CHECK ((status = 'PENDING' AND next_retry_at IS NOT NULL) OR status <> 'PENDING'),
    CONSTRAINT chk_recharge_reservation_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_recharge_reservation_outbox_status CHECK (status IN ('PENDING', 'DONE', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='충전 직후 재예약을 기다리는 행. 결제당 한 행이고 충전한 영업일이 지나면 닫는다';
