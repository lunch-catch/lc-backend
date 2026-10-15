-- 기존 진행 데이터가 없는 상태에서 통합 단계와 가게 ID 유무에 대한 제약을 변경한다.
ALTER TABLE store_registration_progress
    DROP CHECK chk_store_registration_progress_step,
    DROP CHECK chk_store_registration_progress_store;

ALTER TABLE store_registration_progress
    MODIFY COLUMN resume_step VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'TERMS'
        COMMENT '저장된 내용을 기준으로 이어서 진행할 단계(약관 저장 후 STORE_INFO, 가게 정보 등록 및 사업자 검증 완료 후 LOCATION)',
    ADD CONSTRAINT chk_store_registration_progress_step CHECK (
        resume_step IN ('TERMS', 'STORE_INFO', 'LOCATION', 'HOURS', 'IMAGES', 'MENU')),
    ADD CONSTRAINT chk_store_registration_progress_store CHECK (
        (resume_step IN ('TERMS', 'STORE_INFO') AND store_id IS NULL)
        OR (resume_step IN ('LOCATION', 'HOURS', 'IMAGES', 'MENU') AND store_id IS NOT NULL));