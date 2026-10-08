ALTER TABLE template
    ADD COLUMN last_modified_by BIGINT NULL COMMENT '계정 도메인 - 관리자 테이블 참조(마지막으로 임시저장한 관리자)' AFTER activated_at,
    ADD COLUMN last_modified_at DATETIME(6) NULL COMMENT '마지막 임시저장 시각' AFTER last_modified_by;
