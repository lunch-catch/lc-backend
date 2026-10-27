-- ----------------------------------------------------------------------------
-- impression_log  |  정산에 넘긴 것을 노출 행에 남긴다 (노출 수집 v2, #1010)
--   노출 요청은 정산을 부르지 않고 노출만 저장한다. 릴레이가 dispatched_at 이 빈 행을 모아 정산에 넘긴다
--   무효 노출과 소진 노출은 넘기지 않으므로 저장할 때 받은 시각으로 채운다. 비어 있으면 곧 넘겨야 할 유효 노출이다
--   열은 끝에 더해 테이블을 복사하지 않는다(INSTANT). 인덱스는 쓰기를 막지 않고 만든다(INPLACE, LOCK=NONE)
-- ----------------------------------------------------------------------------
-- 기존 행은 노출 수집 v1 이 요청 안에서 이미 차감했으므로 넘긴 것으로 보여야 한다. 행을 고치는 UPDATE 를 쓰지 않는다(#1010)
--   행이 많으면 UPDATE 가 마이그레이션 연결의 시간 제한을 넘겨, 열만 남고 Flyway 는 실패로 남는다
--   INSTANT 로 더한 열은 기존 행이 더할 때의 기본값을 읽는다. 그 기본값을 넘긴 표시(2000-01-01)로 두고 바로 NULL 로 바꾼다
--   그 뒤 저장되는 행은 기본값이 NULL 이고, 노출 저장이 넘길 필요 없는 노출만 받은 시각으로 채운다
ALTER TABLE impression_log
    ADD COLUMN dispatched_at     DATETIME(6) DEFAULT '2000-01-01 00:00:00' COMMENT '정산에 넘겨 판정이 커밋된 시각. 넘길 필요가 없는 노출은 받은 시각. V8 전의 행은 2000-01-01. NULL 이면 넘겨야 할 유효 노출',
    ADD COLUMN dispatch_attempts TINYINT NOT NULL DEFAULT 0 COMMENT '정산에 넘겼다가 실패한 횟수. 10에 닿으면 릴레이가 건너뛴다',
    ADD COLUMN next_dispatch_at  DATETIME(6) COMMENT '실패한 노출을 다시 넘길 수 있는 시각. 지수 백오프와 Full Jitter 로 미룬다. NULL 이면 바로 넘긴다',
    ALGORITHM = INSTANT;

ALTER TABLE impression_log
    ALTER COLUMN dispatched_at SET DEFAULT NULL,
    ALGORITHM = INSTANT;

ALTER TABLE impression_log
    ADD INDEX idx_imp_dispatch (dispatched_at, created_at),
    ALGORITHM = INPLACE, LOCK = NONE;
