-- ----------------------------------------------------------------------------
-- kakao_unlink_failure  |  재시도 종료 사유 (2026-10-07)
--   아웃박스 행을 탈퇴 트랜잭션에서 먼저 만들게 되어(유실 방지), "아직 처리하지 않은 것"과
--   "더 이상 재시도하지 않는 것"을 가려야 한다. resolved 가 그 구분을 맡고 stop_reason 이
--   멈춘 이유를 남긴다. 한도 소진과 카카오의 영구 거부는 운영 대응이 달라 구분한다.
--
--   attempt_count 는 관측용으로만 남고 조회 조건에서 빠진다. 한도를 상수로 올렸을 때
--   예전에 포기한 행이 되살아나는 것을 막는다.
--
--   두 CHECK 는 batch_execution_log 의 retry_count/retry_reason 과 같은 모양이다(ops/V2).
--   인덱스는 조회의 ORDER BY(created_at, PK)에 맞춘다. 범위 조건을 앞에 두지 않아야
--   정렬까지 인덱스로 끝난다.
-- ----------------------------------------------------------------------------
ALTER TABLE kakao_unlink_failure
    ADD COLUMN stop_reason VARCHAR(20) NULL
        COMMENT '재시도 종료 사유. REJECTED(카카오가 영구 거부) | EXHAUSTED(재시도 한도 소진). resolved=TRUE 일 때만 값이 있다'
        AFTER resolved,
    ADD CONSTRAINT chk_kakao_unlink_failure_stop
        CHECK ((resolved = FALSE AND stop_reason IS NULL)
            OR (resolved = TRUE AND stop_reason IS NOT NULL)),
    ADD CONSTRAINT chk_kakao_unlink_failure_stop_reason
        CHECK (stop_reason IS NULL OR stop_reason IN ('REJECTED', 'EXHAUSTED')),
    ADD KEY idx_kakao_unlink_failure_retry (resolved, created_at, kakao_unlink_failure_id);
