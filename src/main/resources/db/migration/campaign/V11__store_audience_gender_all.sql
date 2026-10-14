-- ----------------------------------------------------------------------------
-- store_audience  |  gender 값 집합을 MALE/FEMALE/OTHER에서 MALE/FEMALE/ALL로 바꾼다 (2026-10-13)
--   store_audience는 캠페인이 실제로 타겟팅할 수 있는 성별(campaign.contract.TargetGender,
--   ALL/MALE/FEMALE)을 담는 타겟팅용 테이블이다. 회원 자신의 성별(member_profile.gender,
--   OTHER 포함)과는 다른 개념이라 그 값 집합을 그대로 따라갈 이유가 없다. OTHER는 지금까지
--   실제로 쓰인 적이 없어 기존 데이터 이관이 필요 없다 (#218)
-- ----------------------------------------------------------------------------
ALTER TABLE store_audience
    MODIFY COLUMN gender VARCHAR(10) NOT NULL
        COMMENT 'MALE/FEMALE/ALL. campaign.contract.TargetGender와 같은 값',
    DROP CHECK chk_store_audience_gender,
    ADD CONSTRAINT chk_store_audience_gender CHECK (gender IN ('MALE', 'FEMALE', 'ALL'));
