/*
 * 템플릿 생성 요청의 재시도를 가려내는 멱등키다.
 * 클라이언트가 만들어 보내고, 같은 값으로 다시 오면 새 버전을 만들지 않고 기존 응답을 돌려준다.
 * NOT NULL 과 UNIQUE 를 한 번에 걸면, 이미 행이 있는 환경에서 MySQL 의 암묵 기본값('')이
 * 겹쳐 UNIQUE 생성이 실패할 수 있다. 그래서 NULL 로 더하고 기존 행을 채운 뒤
 * 제약을 거는 세 단계로 나눈다.
 */
ALTER TABLE template_version
    ADD COLUMN request_id VARCHAR(64) NULL COMMENT '템플릿 생성 요청 멱등키' AFTER request_prompt;

/*
 * 이 컬럼이 생기기 전에 만들어진 행은 유일성이 있는 값으로 채운다.
 * PK 기반 값(예: legacy-1)은 예측 가능해서 관리자가 요청에 그대로 넣으면 지금의 검증을
 * 거치지 않은 예전 버전이 그대로 재시도 응답으로 나갈 수 있다. UUID() 로 채워 그 값을
 * 추측할 수 없게 한다.
 */
UPDATE template_version
SET request_id = UUID()
WHERE request_id IS NULL;

ALTER TABLE template_version
    MODIFY COLUMN request_id VARCHAR(64) NOT NULL COMMENT '템플릿 생성 요청 멱등키',
    ADD UNIQUE KEY uk_template_version_request_id (request_id);
