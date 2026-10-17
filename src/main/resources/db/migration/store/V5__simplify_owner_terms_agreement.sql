-- 전체 필수 약관의 동의 시각을 점주당 한 행에 저장한다.
ALTER TABLE owner_terms_agreement
    ADD COLUMN agreed_at DATETIME(6) NULL COMMENT '전체 필수 약관 동의 시각';

/*
 * 기존 모든 기록이 동의 상태이고 철회되지 않은 점주만 동의 시각을 이관한다.
 * 기존 시각은 마지막 갱신 시각을 사용하며 미동의나 철회가 섞인 기록은 동의로 승격하지 않는다.
 */
CREATE TEMPORARY TABLE owner_terms_agreement_snapshot AS
SELECT owner_id, MIN(owner_terms_agreement_id) AS retained_id,
       MIN(agreed AND withdrawn_at IS NULL) AS agreed,
       CASE WHEN MIN(agreed AND withdrawn_at IS NULL) = 1 THEN MAX(updated_at) ELSE NULL END AS agreed_at,
       MAX(withdrawn_at) AS withdrawn_at,
       MIN(created_at) AS created_at, MAX(updated_at) AS updated_at
FROM owner_terms_agreement
GROUP BY owner_id;

UPDATE owner_terms_agreement a
JOIN owner_terms_agreement_snapshot s ON a.owner_terms_agreement_id = s.retained_id
SET a.required = TRUE, a.agreed = s.agreed,
    a.agreed_at = s.agreed_at, a.withdrawn_at = s.withdrawn_at,
    a.created_at = s.created_at, a.updated_at = s.updated_at;

DELETE a FROM owner_terms_agreement a
JOIN owner_terms_agreement_snapshot s ON a.owner_id = s.owner_id
WHERE a.owner_terms_agreement_id <> s.retained_id;

DROP TEMPORARY TABLE owner_terms_agreement_snapshot;

ALTER TABLE owner_terms_agreement
    DROP INDEX uk_owner_terms_version,
    DROP COLUMN terms_type,
    DROP COLUMN terms_version,
    MODIFY COLUMN withdrawn_at DATETIME(6) NULL COMMENT '동의 철회 시각',
    ADD CONSTRAINT uk_owner_terms_agreement_owner UNIQUE (owner_id);