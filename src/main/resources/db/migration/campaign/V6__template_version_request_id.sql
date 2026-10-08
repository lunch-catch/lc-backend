/*
 * 템플릿 생성 요청의 재시도를 가려내는 멱등키다.
 * 클라이언트가 만들어 보내고, 같은 값으로 다시 오면 새 버전을 만들지 않고 기존 응답을 돌려준다.
 * NOT NULL 과 UNIQUE 를 한 번에 걸면, 이미 행이 있는 환경에서 MySQL 의 암묵 기본값('')이
 * 겹쳐 UNIQUE 생성이 실패할 수 있다. 그래서 NULL 로 더하고 기존 행을 PK 로 채운 뒤
 * 제약을 거는 세 단계로 나눈다.
 */
ALTER TABLE template_version
    ADD COLUMN request_id VARCHAR(64) NULL COMMENT '템플릿 생성 요청 멱등키' AFTER request_prompt;

-- 이 컬럼이 생기기 전에 만들어진 행은 자신의 PK 로 값을 채워 유일성을 보장한다.
UPDATE template_version
SET request_id = CONCAT('legacy-', template_version_id)
WHERE request_id IS NULL;

ALTER TABLE template_version
    MODIFY COLUMN request_id VARCHAR(64) NOT NULL COMMENT '템플릿 생성 요청 멱등키',
    ADD UNIQUE KEY uk_template_version_request_id (request_id);
