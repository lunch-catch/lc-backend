package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.campaign.AbstractCampaignIntegrationTest;
import com.launchcatch.campaign.template.dto.TemplateCreateResponse;
import com.launchcatch.campaign.template.dto.TemplateDetailResponse;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.entity.TemplateStatus;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import com.launchcatch.campaign.template.repository.TemplateVersionRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/*
 * 게시된 템플릿을 채팅으로 다시 수정해 재게시하는 흐름(#880)을 실제 MySQL 로 확인한다. 수정과 게시는 서비스를 그대로
 * 부르고, 04시 삭제 배치는 저장소의 삭제 쿼리를 직접 불러 흉내 낸다.
 */
class TemplatePublishedEditIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final Long ADMIN_ID = 1L;
    private static final String HTML = "<div>"
            + "<h1 data-slot=\"eventName\">title</h1>"
            + "<p data-slot=\"discount\">할인 내용</p>"
            + "<p data-slot=\"period\">기간</p>"
            + "<img data-slot=\"image\" alt=\"메뉴 이미지\">"
            + "</div>";

    @Autowired
    private TemplateReviseService templateReviseService;

    @Autowired
    private TemplatePublishService templatePublishService;

    @Autowired
    private TemplateRenameService templateRenameService;

    @Autowired
    private TemplateQueryService templateQueryService;

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private TemplateVersionRepository templateVersionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 게시된_템플릿을_수정해_재게시해도_active가_유지되고_번호가_줄지_않는다() {
        Long templateId = publishedActiveTemplate();

        TemplateCreateResponse first = templateReviseService.revise(templateId, "버튼 색 바꿔줘", requestId(), ADMIN_ID);

        assertThat(first.versionNumber()).isEqualTo(2);
        assertThat(first.status()).isEqualTo(TemplateStatus.PUBLISHED);
        Template whileEditing = templateRepository.findById(templateId).orElseThrow();
        assertThat(whileEditing.getHtmlContent()).isEqualTo(HTML);
        assertThat(whileEditing.getPublishedVersionNumber()).isEqualTo(1);
        assertThat(whileEditing.isActive()).isTrue();
        TemplateDetailResponse detail = templateQueryService.detail(templateId);
        assertThat(detail.hasDraft()).isTrue();
        assertThat(detail.html()).isEqualTo(HTML);

        templatePublishService.publish(templateId, 2, ADMIN_ID);
        entityManager.flush();
        entityManager.clear();

        Template republished = templateRepository.findById(templateId).orElseThrow();
        assertThat(republished.getPublishedVersionNumber()).isEqualTo(2);
        assertThat(republished.isActive()).isTrue();
        assertThat(republished.getHtmlContent()).isNotEqualTo(HTML);
        assertThat(republished.getLastModifiedBy()).isEqualTo(ADMIN_ID);
        assertThat(templateVersionRepository.existsByTemplateIdAndDeletedAtIsNull(templateId)).isFalse();
        assertThat(templateQueryService.detail(templateId).hasDraft()).isFalse();

        // 04시 배치가 소프트삭제된 버전을 지운 뒤에도 번호는 게시한 번호 다음에서 이어진다.
        templateVersionRepository.deleteBatchByDeletedAtIsNotNull(500);
        entityManager.clear();
        TemplateCreateResponse afterPurge = templateReviseService.revise(templateId, "배경 바꿔줘", requestId(), ADMIN_ID);

        assertThat(afterPurge.versionNumber()).isEqualTo(3);
    }

    @Test
    void 게시된_템플릿의_이름을_바꿔도_게시본과_active는_그대로다() {
        Long templateId = publishedActiveTemplate();

        templateRenameService.rename(templateId, "겨울 신메뉴", ADMIN_ID);
        entityManager.flush();
        entityManager.clear();

        Template renamed = templateRepository.findById(templateId).orElseThrow();
        assertThat(renamed.getName()).isEqualTo("겨울 신메뉴");
        assertThat(renamed.getHtmlContent()).isEqualTo(HTML);
        assertThat(renamed.isActive()).isTrue();
    }

    private Long publishedActiveTemplate() {
        Template template = Template.createDraft("가을 신메뉴");
        template.addDraftVersion(ADMIN_ID, LocalDateTime.now(), "처음 요청", requestId(), HTML, 1);
        templateRepository.saveAndFlush(template);
        Long templateId = template.getId();
        templatePublishService.publish(templateId, 1, ADMIN_ID);
        Template published = templateRepository.findById(templateId).orElseThrow();
        published.changeActive(true, ADMIN_ID, LocalDateTime.now());
        templateRepository.saveAndFlush(published);
        entityManager.clear();
        return templateId;
    }

    private String requestId() {
        return "req-" + System.nanoTime();
    }
}
