-- ----------------------------------------------------------------------------
-- batch_execution_log  |  일시적 오류 자동 재실행 기록 (2026-10-06)
--   일시적 오류(교착, 락 대기 시간 초과, 연결 오류, Valkey 응답 시간 초과)는
--   30초, 1분 간격으로 최대 2회 자동 재실행한다(명세서 102행 I열)
-- ----------------------------------------------------------------------------
ALTER TABLE batch_execution_log
    ADD COLUMN retry_count   INT           NOT NULL DEFAULT 0  COMMENT '일시적 오류로 자동 재실행한 횟수(최대 2). 수동 재실행 시 0으로 되돌린다' AFTER owner_id,
    ADD COLUMN retry_reason  VARCHAR(500)                      COMMENT '마지막 자동 재실행 사유. 재실행이 없으면 NULL' AFTER retry_count,
    ADD CONSTRAINT chk_batch_retry CHECK ((retry_count BETWEEN 0 AND 2) AND ((retry_count = 0 AND retry_reason IS NULL) OR (retry_count > 0 AND retry_reason IS NOT NULL)));
