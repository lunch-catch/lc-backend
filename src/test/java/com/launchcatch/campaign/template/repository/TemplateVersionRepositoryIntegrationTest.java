package com.launchcatch.campaign.template.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.campaign.AbstractCampaignIntegrationTest;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.entity.TemplateVersion;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * deleteBatchByDeletedAtIsNotNull이 트랜잭션 없이 불려도 동작하는지 실제 DB로 확인한다
 * (#412, 코드래빗 리뷰). 운영에서는 TemplateVersionPurgeService가 트랜잭션 없이 이 메서드를
 * 직접 반복 호출하므로, 클래스 단위 @Transactional(AbstractCampaignIntegrationTest)을
 * 테스트 메서드에서 NOT_SUPPORTED로 덮어 같은 상황을 만든다. 자동 롤백이 안 되니
 * @AfterEach로 직접 지운다.
 */
class TemplateVersionRepositoryIntegrationTest extends AbstractCampaignIntegrationTest {

    @Autowired private TemplateVersionRepository templateVersionRepository;
    @Autowired private TemplateRepository templateRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManager entityManager;

    private Long templateId;

    @AfterEach
    void cleanUp() {
        if (templateId != null) {
            transactionTemplate.executeWithoutResult(status -> {
                entityManager.createNativeQuery("delete from template_version where template_id = :id")
                        .setParameter("id", templateId)
                        .executeUpdate();
                entityManager.createNativeQuery("delete from template where template_id = :id")
                        .setParameter("id", templateId)
                        .executeUpdate();
            });
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 트랜잭션_없이_불러도_소프트삭제된_행을_지운다() {
        LocalDateTime now = LocalDateTime.now(clock);
        templateId = transactionTemplate.execute(status -> {
            Template template = Template.createDraft("코드래빗 검증용 템플릿");
            template.addDraftVersion(1L, now, "요청", "req-" + UUID.randomUUID(), "<html></html>", 1);
            templateRepository.saveAndFlush(template);
            templateVersionRepository.softDeleteAllByTemplateId(template.getId(), now);
            return template.getId();
        });

        int deleted = templateVersionRepository.deleteBatchByDeletedAtIsNotNull(500);

        assertThat(deleted).isEqualTo(1);
    }
}
