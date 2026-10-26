-- ----------------------------------------------------------------------------
-- impression_log  |  정산에 넘긴 것을 노출 행에 남긴다 (노출 수집 v2, #1010)
--   노출 요청은 정산을 부르지 않고 노출만 저장한다. 릴레이가 dispatched_at 이 빈 행을 모아 정산에 넘긴다
--   무효 노출과 소진 노출은 넘기지 않으므로 저장할 때 받은 시각으로 채운다. 비어 있으면 곧 넘겨야 할 유효 노출이다
--   열은 끝에 더해 테이블을 복사하지 않는다(INSTANT). 인덱스는 쓰기를 막지 않고 만든다(INPLACE, LOCK=NONE)
-- ----------------------------------------------------------------------------
ALTER TABLE impression_log
    ADD COLUMN dispatched_at     DATETIME(6) COMMENT '정산에 넘겨 판정이 커밋된 시각. 넘길 필요가 없는 노출은 받은 시각. NULL 이면 넘겨야 할 유효 노출',
    ADD COLUMN dispatch_attempts TINYINT NOT NULL DEFAULT 0 COMMENT '정산에 넘겼다가 실패한 횟수. 10에 닿으면 릴레이가 건너뛴다',
    ALGORITHM = INSTANT;

ALTER TABLE impression_log
    ADD INDEX idx_imp_dispatch (dispatched_at, created_at),
    ALGORITHM = INPLACE, LOCK = NONE;

-- 릴레이는 어제 00:00 이후 행만 읽는다. 그 안의 기존 행은 노출 수집 v1 이 요청 안에서 이미 차감했으므로 넘긴 것으로 채운다
UPDATE impression_log
   SET dispatched_at = created_at
 WHERE created_at >= CURRENT_DATE - INTERVAL 1 DAY
   AND dispatched_at IS NULL;
