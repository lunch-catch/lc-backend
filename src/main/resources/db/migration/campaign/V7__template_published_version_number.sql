/*
 * 게시 재시도를 가려내는 멱등키다. 템플릿 게시는 LLM 을 부르지 않아 결과가
 * (templateId, versionNumber) 로만 결정된다. 이미 게시된 템플릿에 같은
 * versionNumber 로 다시 게시 요청이 오면 재시도로 보고 기존 결과를 그대로
 * 돌려주기 위해, 게시에 쓴 버전 번호를 템플릿에 남겨 둔다.
 */
ALTER TABLE template
    ADD COLUMN published_version_number INT NULL COMMENT '게시에 쓴 버전 번호. 게시 재시도 식별용(게시 전이면 NULL)' AFTER published_at;

ALTER TABLE template
    DROP CHECK chk_template_status,
    ADD CONSTRAINT chk_template_status CHECK (
        (status = 'DRAFT' AND published_at IS NULL AND published_version_number IS NULL)
        OR (status = 'PUBLISHED' AND published_at IS NOT NULL AND published_version_number IS NOT NULL)
    );
