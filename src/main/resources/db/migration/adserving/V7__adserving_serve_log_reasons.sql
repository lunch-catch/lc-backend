-- ----------------------------------------------------------------------------
-- serve_log  |  카드마다 배정 근거를 남긴다 (2026-10-25, #977)
--   배정 진단(FR-027)과 가중치를 바꾼 효과의 비교에 쓴다. 판정과 과금에는 쓰지 않는다
--   progress_rate 는 신규 우선 보정 전 진행률이고 예산을 읽지 못했으면 NULL 이다
--   engine_version 은 배정 규칙의 버전이다. 이 마이그레이션 전의 행은 NULL 이다
--   열을 끝에 더해 테이블을 복사하지 않는다(INSTANT). 복사가 필요해지면 배포가 실패해 쓰기가 멈추지 않는다
-- ----------------------------------------------------------------------------
ALTER TABLE serve_log
    ADD COLUMN progress_rate   DECIMAL(8, 4) COMMENT '배정 때의 예산 소진 진행률. 예산을 읽지 못했으면 NULL',
    ADD COLUMN relevance_score DECIMAL(5, 4) COMMENT '배정 때의 관련성 점수 0~1',
    ADD COLUMN engine_version  VARCHAR(20)   COMMENT '배정 규칙의 버전',
    ALGORITHM = INSTANT;
