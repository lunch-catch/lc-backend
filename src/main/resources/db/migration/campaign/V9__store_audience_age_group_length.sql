-- ----------------------------------------------------------------------------
-- store_audience  |  age_group 길이를 VARCHAR(10)에서 VARCHAR(20)으로 넓힌다 (2026-10-13)
--   AGE_50_PLUS가 11자라 VARCHAR(10)에 들어가지 않아 저장이 실패했다. status, discount_type과
--   같은 코드값 컬럼 관례(VARCHAR(20))를 맞춘다.
-- ----------------------------------------------------------------------------
ALTER TABLE store_audience
    MODIFY COLUMN age_group VARCHAR(20) NOT NULL
        COMMENT 'AGE_20S/AGE_30S/AGE_40S/AGE_50_PLUS. member_profile.age_group와 같은 값';
