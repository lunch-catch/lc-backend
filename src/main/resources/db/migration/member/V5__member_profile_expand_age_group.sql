ALTER TABLE member_profile
    MODIFY COLUMN age_group VARCHAR(20) NULL COMMENT '[2026-10-01 확정] AGE_20S/AGE_30S/AGE_40S/AGE_50_PLUS. 캠페인 도메인(노출 대상 판정, 00:00 (10)단계 인원 집계)과 코드 통일';
