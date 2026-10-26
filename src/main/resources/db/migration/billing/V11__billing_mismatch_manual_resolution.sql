/*
 * settlement_mismatch  |  관리자의 두 해결 방법 (#1013)
 * MANUAL_ADJUSTMENT 는 관리자가 양수나 음수 조정으로 금액을 바로잡은 건이다. 양수만 받던 OVERCHARGE_REVERSAL 을 대신한다.
 * NO_ADJUSTMENT_REQUIRED 는 관리자가 원장을 바꾸지 않고 닫은 건이다.
 * 두 값 모두 사람이 닫으므로 관리자 ID 를 요구한다.
 * 기존 OVERCHARGE_REVERSAL 행은 양수 조정이라 뜻을 잃지 않고 MANUAL_ADJUSTMENT 로 바꾼다.
 * OVERCHARGE_REVERSAL 은 이 파일에서 허용 목록에 남긴다. 기본 데이터 V5 가 이 파일보다 뒤에 그 값을 넣고(빈 DB), 롤링 배포 중의 옛 인스턴스도 그 값을 쓴다.
 * 같은 이름의 CHECK 는 한 ALTER 에서 DROP 과 ADD 를 함께 하지 못해 문장을 나눈다(V2 와 같다).
 */
ALTER TABLE settlement_mismatch DROP CHECK chk_settlement_mismatch_resolution_type;

ALTER TABLE settlement_mismatch DROP CHECK chk_settlement_mismatch_resolved_by;

UPDATE settlement_mismatch
   SET resolution_type = 'MANUAL_ADJUSTMENT'
 WHERE resolution_type = 'OVERCHARGE_REVERSAL';

ALTER TABLE settlement_mismatch
    ADD CONSTRAINT chk_settlement_mismatch_resolution_type
        CHECK (resolution_type IS NULL
            OR resolution_type IN ('CACHE_REBUILD', 'LEDGER_CORRECTION', 'OVERCHARGE_REVERSAL',
                                   'MANUAL_ADJUSTMENT', 'NO_ADJUSTMENT_REQUIRED')),
    ADD CONSTRAINT chk_settlement_mismatch_resolved_by
        CHECK (resolution_type IS NULL
            OR resolution_type IN ('CACHE_REBUILD', 'LEDGER_CORRECTION')
            OR resolved_by IS NOT NULL);
