-- ----------------------------------------------------------------------------
-- template_version  |  deleted_at 단일 인덱스 (#412)
--   TemplateVersionPurgeScheduler가 매일 소프트삭제된(deleted_at IS NOT NULL) 행을
--   지운다. 인덱스가 없으면 InnoDB가 이 조건을 평가하려고 테이블 전체를 스캔하면서
--   스캔한 모든 행에 잠금을 걸어, TemplatePublishService.publish()의 소프트삭제
--   UPDATE나 TemplateCreateService/TemplateReviseService의 INSERT와 부딪혀 락 경합과
--   교착 상태를 만들 수 있다.
-- ----------------------------------------------------------------------------
ALTER TABLE template_version
    ADD KEY idx_template_version_deleted_at (deleted_at);
