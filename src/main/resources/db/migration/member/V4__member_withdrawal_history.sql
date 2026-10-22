CREATE TABLE member_withdrawal_history (
    member_withdrawal_history_id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    provider_user_id VARCHAR(100) NOT NULL,
    withdrawn_at DATETIME(6) NOT NULL,
    suspended_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (member_withdrawal_history_id),
    UNIQUE KEY uk_member_withdrawal_member (member_id),
    KEY idx_member_withdrawal_provider_time (provider_user_id, withdrawn_at),
    CONSTRAINT fk_member_withdrawal_member FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE member MODIFY provider_user_id VARCHAR(100) NULL COMMENT '현재 연결된 카카오 회원번호';

INSERT INTO member_withdrawal_history
    (member_id, provider_user_id, withdrawn_at, suspended_at, created_at, updated_at)
SELECT member_id, provider_user_id, withdrawn_at, suspended_at, withdrawn_at, updated_at
FROM member
WHERE status = 'WITHDRAWN';

UPDATE member SET provider_user_id = NULL WHERE status = 'WITHDRAWN';

ALTER TABLE member ADD CONSTRAINT chk_member_provider_state CHECK (
    (status = 'WITHDRAWN' AND provider_user_id IS NULL)
    OR (status <> 'WITHDRAWN' AND provider_user_id IS NOT NULL)
);

ALTER TABLE kakao_unlink_failure
    ADD COLUMN processing BOOLEAN NOT NULL DEFAULT FALSE,
    ADD KEY idx_kakao_unlink_provider (provider_user_id),
    DROP KEY idx_kakao_unlink_failure_retry,
    ADD KEY idx_kakao_unlink_failure_retry (resolved, processing, created_at, kakao_unlink_failure_id);
