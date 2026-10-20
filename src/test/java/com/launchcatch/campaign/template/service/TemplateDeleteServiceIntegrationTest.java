package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.campaign.AbstractCampaignIntegrationTest;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.repository.TemplateQuotaRepository;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import com.launchcatch.campaign.template.repository.TemplateVersionRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/*
 * 단위 테스트(TemplateDeleteServiceTest)는 저장소를 목으로 둬서 실제 하드삭제 쿼리와
 * template_quota 의 조건부 UPDATE 가 실제 MySQL 에서 어떻게 도는지는 보지 않는다. 여기서는
 * 실제로 저장한 템플릿과 버전이 지워지는지, current_count 가 실제로 줄어드는지를 본다(#517,
 * 완료 조건 1, 2).
 */
class TemplateDeleteServiceIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final Long ADMIN_ID = 1L;

    @Autowired
    private TemplateDeleteService templateDeleteService;

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private TemplateVersionRepository templateVersionRepository;

    @Autowired
    private TemplateQuotaRepository templateQuotaRepository;

    private Template draftTemplateWithVersions(int count) {
        Template template = Template.createDraft("가을 신메뉴");
        LocalDateTime now = LocalDateTime.now();
        for (int number = 1; number <= count; number++) {
            template.addDraftVersion(ADMIN_ID, now, "요청 " + number, "req-" + number + "-" + System.nanoTime(),
                    "<div>" + number + "</div>", number);
        }
        return templateRepository.saveAndFlush(template);
    }

    private int currentCount() {
        return templateQuotaRepository.findAll().get(0).getCurrentCount();
    }

    @Test
    void DRAFT_템플릿을_삭제하면_템플릿과_버전이_모두_없어지고_생성_가능_수가_돌아간다() {
        Template template = draftTemplateWithVersions(2);
        Long templateId = template.getId();
        int countBeforeDelete = currentCount();

        templateDeleteService.delete(templateId);

        assertThat(templateRepository.findById(templateId)).isEmpty();
        assertThat(templateVersionRepository.findFirstByTemplateIdOrderByVersionNumberDesc(templateId))
                .isEmpty();
        assertThat(currentCount()).isEqualTo(countBeforeDelete - 1);
    }

    @Test
    void 한도가_찬_상태에서_하나를_지우면_생성_가능_수가_다시_확보된다() {
        Template template = draftTemplateWithVersions(1);
        Long templateId = template.getId();
        // tryReserve(max)가 current_count < max일 때만 올리므로, max를 지금 값과 같게 줘서
        // "한도가 꽉 찬 상태"를 만든다.
        int full = currentCount();
        assertThat(templateQuotaRepository.tryReserve(full)).isZero();

        templateDeleteService.delete(templateId);

        assertThat(templateQuotaRepository.tryReserve(full)).isEqualTo(1);
    }
}
