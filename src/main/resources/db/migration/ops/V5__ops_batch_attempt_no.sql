-- ----------------------------------------------------------------------------
-- batch_execution_log  |  실행 세대 기록 (2026-10-16)
--   claim, 이어받기, 수동 재실행(retry, rerun)마다 하나씩 오른다. 임대를 잃은 서버가 깨어나
--   결과를 공개하려 할 때 그 트랜잭션 안에서 세대가 지금도 맞는지 확인하는 데 쓴다(#360)
-- ----------------------------------------------------------------------------
ALTER TABLE batch_execution_log
    ADD COLUMN attempt_no INT NOT NULL DEFAULT 1 COMMENT '실행 세대. claim=1, 이어받기·수동 재실행마다 +1' AFTER retry_reason,
    ADD CONSTRAINT chk_batch_attempt CHECK (attempt_no >= 1);
