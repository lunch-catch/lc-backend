-- 점주 계정과 같은 도메인의 세션 사이 참조 무결성을 보장한다.
ALTER TABLE owner_session
    MODIFY COLUMN owner_id BIGINT NOT NULL COMMENT '점주 ID',
    ADD CONSTRAINT fk_owner_session_owner
    FOREIGN KEY (owner_id) REFERENCES owner (owner_id);
