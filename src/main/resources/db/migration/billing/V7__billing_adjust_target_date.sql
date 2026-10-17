-- point_ledger  |  ADJUST 가 가리키는 정정 대상 영업일을 담는다 (2026-10-16, #402)
--   과오 차감 정정은 원래 과금된 영업일의 합계에 붙어야 하는데 ADJUST 행에는 그 날짜를 담는 곳이 없었다.
--   business_date 는 RESERVE 와 RELEASE 의 예약 영업일이고 chk_point_ledger_business_date 가 그 둘로 묶는다.
--   의미가 다른 값을 같은 컬럼에 섞지 않으려고 새 컬럼을 둔다.
--   캠페인에 연결된 ADJUST 만 값을 가진다. 이미 기록된 ADJUST 는 NULL 로 두고 집계가 기록 시각의 KST 날짜로 대체한다.
ALTER TABLE point_ledger
    ADD COLUMN adjust_target_date DATE NULL COMMENT 'ADJUST 만 값 존재. 정정이 가리키는 원래 영업일. 캠페인 없는 ADJUST 와 이전 행은 NULL' AFTER business_date,
    ADD CONSTRAINT chk_point_ledger_adjust_target_date CHECK (adjust_target_date IS NULL OR entry_type = 'ADJUST');
