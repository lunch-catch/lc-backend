-- 점주 ID는 도메인 간 소프트 참조이며 가게 ID는 store 도메인 안의 FK다.
CREATE TABLE store_registration_progress (
    store_registration_progress_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '가게 등록 진행 상태 PK',
    owner_id BIGINT NOT NULL COMMENT '가게 등록 중인 점주 ID',
    store_id BIGINT NULL COMMENT '등록 중인 가게 ID. 기본 정보 저장 전에는 NULL',
    resume_step VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'TERMS'
        COMMENT '저장된 내용을 기준으로 이어서 진행할 단계(약관 저장 후 STORE, 가게 기본 정보 저장 후 LOCATION)',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '동시 수정 확인 버전',
    created_at DATETIME(6) NOT NULL COMMENT '생성일시',
    updated_at DATETIME(6) NOT NULL COMMENT '수정일시',
    PRIMARY KEY (store_registration_progress_id),
    UNIQUE KEY uk_store_registration_progress_owner (owner_id),
    UNIQUE KEY uk_store_registration_progress_store (store_id),
    CONSTRAINT fk_store_registration_progress_store FOREIGN KEY (store_id) REFERENCES store (store_id),
    CONSTRAINT chk_store_registration_progress_step CHECK (
        resume_step IN ('TERMS', 'STORE', 'LOCATION', 'BUSINESS_VERIFICATION', 'HOURS', 'IMAGES', 'MENU')),
    CONSTRAINT chk_store_registration_progress_store CHECK (
        (resume_step IN ('TERMS', 'STORE') AND store_id IS NULL)
        OR (resume_step NOT IN ('TERMS', 'STORE') AND store_id IS NOT NULL)),
    CONSTRAINT chk_store_registration_progress_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
