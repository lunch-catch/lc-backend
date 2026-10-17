package com.launchcatch.campaign.template.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.campaign.AbstractCampaignIntegrationTest;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.entity.TemplateVersion;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 손으로 쓴 @Query 가 실제 MySQL 에서 번역되고, 의도한 행만 건드리는지 본다(#410).
 * 단위 테스트는 저장소를 목으로 두므로 이 둘은 보장하지 않는다.
 *
 * deleteBatchByDeletedAtIsNotNull이 트랜잭션 없이 불려도 동작하는지도 실제 DB로 확인한다
 * (#412, 코드래빗 리뷰). 운영에서는 TemplateVersionPurgeService가 트랜잭션 없이 이 메서드를
 * 직접 반복 호출하므로, 그 테스트만 클래스 단위 @Transactional(AbstractCampaignIntegrationTest)을
 * NOT_SUPPORTED로 덮어 같은 상황을 만든다. 자동 롤백이 안 되니 @AfterEach로 직접 지운다.
 */
class TemplateVersionRepositoryIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final Long ADMIN_ID = 1L;
    private static final String HTML = "<div data-slot=\"eventName\">title</div>";

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private TemplateVersionRepository templateVersionRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    private Long templateId;

    private Template templateWithVersions(int count) {
        Template template = Template.createDraft("가을 신메뉴");
        LocalDateTime now = LocalDateTime.now();
        for (int number = 1; number <= count; number++) {
            template.addDraftVersion(ADMIN_ID, now, "요청 " + number, "req-" + number + "-" + System.nanoTime(),
                    HTML, number);
        }
        return templateRepository.saveAndFlush(template);
    }

    private TemplateVersion reload(Long templateId, int versionNumber) {
        return templateVersionRepository.findByTemplateIdAndVersionNumberAndDeletedAtIsNull(templateId, versionNumber)
                .orElse(null);
    }

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
    void 경계값_이하인_살아있는_버전만_지운다() {
        Template template = templateWithVersions(5);

        int deleted = templateVersionRepository.softDeleteUpToVersionNumber(
                template.getId(), 3, LocalDateTime.now());

        assertThat(deleted).isEqualTo(3);
        assertThat(reload(template.getId(), 1)).isNull();
        assertThat(reload(template.getId(), 2)).isNull();
        assertThat(reload(template.getId(), 3)).isNull();
        assertThat(reload(template.getId(), 4)).isNotNull();
        assertThat(reload(template.getId(), 5)).isNotNull();
    }

    @Test
    void 경계값이_0_이하면_아무것도_지우지_않는다() {
        Template template = templateWithVersions(3);

        int deleted = templateVersionRepository.softDeleteUpToVersionNumber(
                template.getId(), 0, LocalDateTime.now());

        assertThat(deleted).isZero();
        assertThat(reload(template.getId(), 1)).isNotNull();
    }

    @Test
    void 소프트삭제된_버전은_requestId로_찾지_못한다() {
        Template template = templateWithVersions(1);
        TemplateVersion version = template.latestVersion();
        String requestId = version.getRequestId();

        templateVersionRepository.softDeleteUpToVersionNumber(template.getId(), 1, LocalDateTime.now());

        Optional<TemplateVersion> found = templateVersionRepository.findByRequestId(requestId);

        assertThat(found).isEmpty();
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
