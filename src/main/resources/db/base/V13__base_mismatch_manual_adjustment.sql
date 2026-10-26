/*
 * 기본 세계의 OVERCHARGE_REVERSAL 대사 건을 MANUAL_ADJUSTMENT 로 바꾼다 (#1013).
 * 정산 마이그레이션 V11 이 같은 일을 하지만 빈 DB 에서는 도메인 마이그레이션이 먼저 돌아 V5 가 넣은 행을 보지 못한다.
 * 이미 적용된 V5 는 체크섬 때문에 고치지 않는다. 그래서 그보다 뒤인 이 파일에서 바꾼다.
 */

UPDATE settlement_mismatch
   SET resolution_type = 'MANUAL_ADJUSTMENT'
 WHERE resolution_type = 'OVERCHARGE_REVERSAL';
