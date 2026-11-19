-- ----------------------------------------------------------------------------
-- impression_log  |  릴레이의 밀린 양 세기가 인덱스만 읽게 한다 (#1280)
--   countPending 은 dispatched_at IS NULL 인 행의 dispatch_abandoned_at 과 next_dispatch_at 을 본다
--   두 열이 인덱스에 없어 밀린 노출마다 행 본문을 다시 읽었다. 대기 노출 20만 건에서 한 번에 약 0.3초였다
--   두 열을 뒤에 더해 커버링 인덱스로 만든다. 앞의 두 열이 같아 findPending 의 순서 읽기는 그대로다
--   같은 이름으로 한 문장에서 바꿔 인덱스가 없는 순간을 두지 않는다. 쓰기를 막지 않고 만든다(INPLACE, LOCK=NONE)
-- ----------------------------------------------------------------------------
ALTER TABLE impression_log
    DROP INDEX idx_imp_dispatch,
    ADD INDEX idx_imp_dispatch (dispatched_at, created_at, dispatch_abandoned_at, next_dispatch_at),
    ALGORITHM = INPLACE, LOCK = NONE;
