/*
 * 템플릿 생성 요청의 재시도를 가려내는 멱등키다.
 * 클라이언트가 만들어 보내고, 같은 값으로 다시 오면 새 버전을 만들지 않고 기존 응답을 돌려준다.
 */
ALTER TABLE template_version
    ADD COLUMN request_id VARCHAR(64) NOT NULL COMMENT '템플릿 생성 요청 멱등키' AFTER request_prompt,
    ADD UNIQUE KEY uk_template_version_request_id (request_id);
