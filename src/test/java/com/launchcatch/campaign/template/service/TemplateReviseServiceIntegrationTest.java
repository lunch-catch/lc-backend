package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.campaign.AbstractCampaignIntegrationTest;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.entity.TemplateVersion;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import com.launchcatch.campaign.template.repository.TemplateVersionRepository;
import com.launchcatch.campaign.template.dto.TemplateCreateResponse;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/*
 * #433 재현/수정 확인. 단위 테스트(TemplateReviseServiceTest)는 저장소를 목으로 둬서
 * 실제 Hibernate 의 merge/persist 경로를 보지 않는다. 여기서는 실제 MySQL 에 먼저 버전
 * 하나를 만들어 flush 하고 entityManager.clear() 로 1차 캐시를 비운 뒤(별도 HTTP 요청처럼
 * "새로 조회된" Template 을 만들기 위해) revise() 를 실제로 부른다.
 */
class TemplateReviseServiceIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final Long ADMIN_ID = 1L;
    private static final String HTML = "<div>"
            + "<span data-slot=\"adLabel\">광고</span>"
            + "<h1 data-slot=\"eventName\">title</h1>"
            + "<p data-slot=\"discount\">할인 내용</p>"
            + "<p data-slot=\"period\">기간</p>"
            + "<img data-slot=\"image\" alt=\"메뉴 이미지\">"
            + "</div>";

    @Autowired
    private TemplateReviseService templateReviseService;

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private TemplateVersionRepository templateVersionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 재조회된_템플릿을_수정해도_저장된다() {
        Template template = Template.createDraft("가을 신메뉴");
        template.addDraftVersion(ADMIN_ID, LocalDateTime.now(), "처음 요청", "req-create-" + System.nanoTime(), HTML, 1);
        templateRepository.saveAndFlush(template);
        Long templateId = template.getId();
        entityManager.clear();

        TemplateCreateResponse response =
                templateReviseService.revise(templateId, "버튼 색 바꿔줘", "req-revise-" + System.nanoTime(), ADMIN_ID);

        assertThat(response.versionNumber()).isEqualTo(2);
        TemplateVersion saved = templateVersionRepository
                .findByTemplateIdAndVersionNumberAndDeletedAtIsNull(templateId, 2)
                .orElseThrow();
        assertThat(saved.getHtmlContent()).isNotBlank();
        Template reloaded = templateRepository.findById(templateId).orElseThrow();
        assertThat(reloaded.getLastModifiedBy()).isEqualTo(ADMIN_ID);
    }
}
