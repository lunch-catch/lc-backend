-- 템플릿 생성 가능 수를 세는 행 하나. 10개 제한을 조건부 UPDATE 로 원자적으로 지키는 데 쓴다.
-- 행은 이 마이그레이션이 한 번만 넣고, 그 뒤로는 애플리케이션이 행을 더 넣지 않는다.
CREATE TABLE template_quota (
                                template_quota_id  BIGINT       NOT NULL AUTO_INCREMENT,
                                current_count       INT          NOT NULL DEFAULT 0,
                                created_at           DATETIME(6)  NOT NULL,
                                updated_at           DATETIME(6)  NOT NULL,

                                PRIMARY KEY (template_quota_id),

                                CONSTRAINT chk_template_quota_single_row CHECK (template_quota_id = 1),
                                CONSTRAINT chk_template_quota_current_count CHECK (current_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 이 마이그레이션이 도는 시점에 이미 만들어진 템플릿이 있을 수 있어 그 수로 시작한다.
INSERT INTO template_quota (current_count, created_at, updated_at)
SELECT COUNT(*), '2026-10-08 00:00:00.000000', '2026-10-08 00:00:00.000000'
FROM template;
