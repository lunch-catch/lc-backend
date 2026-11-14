-- ----------------------------------------------------------------------------
-- point_ledger  |  분석의 그날 미소진 변동이 원장 전체가 아니라 그날 기록된 행만 읽게 한다 (#1242)
--   분석은 미소진 잔액을 앞서 저장한 날의 값에 그날 변동(sumUnspentBetween)을 더해 구한다
--   그날 변동은 기록 시각 범위와 유형으로 거르는데, created_at 이 앞인 인덱스가 없어 원장 전체를 읽었다
--   판정 3일치 300만 행, 원장 271만 행(로컬 MySQL 8.4, 버퍼 풀 128MB)에서 sumUnspentBetween 이 1.90초에서 0.40초,
--   sumUnspentBefore 가 2.07초에서 0.82초가 됐다. 같은 데이터에서 다른 원장 조회의 실행 계획과 시간은 그대로였다
--   amount 를 끝에 두어 합계를 인덱스만으로 구한다
--   ALGORITHM = INPLACE, LOCK = NONE 으로 만드는 동안에도 노출 차감의 쓰기를 막지 않는다
-- ----------------------------------------------------------------------------

/*
 * 원장의 인덱스가 PK 를 포함해 12개에서 13개가 된다.
 * 노출마다 쓰는 DEDUCT INSERT 에 B-tree 하나만큼 쓰기가 늘고, 그 크기는 묶음 차감 부하 시험(#1228)에서 함께 잰다.
 * (entry_type, created_at) 순서는 sumDeductOn 을 2배로 늦췄지만 이 순서는 sumDeductOn 의 시간을 바꾸지 않았다.
 */
ALTER TABLE point_ledger
    ADD INDEX idx_point_ledger_created_type (created_at, entry_type, amount),
    ALGORITHM = INPLACE, LOCK = NONE;
