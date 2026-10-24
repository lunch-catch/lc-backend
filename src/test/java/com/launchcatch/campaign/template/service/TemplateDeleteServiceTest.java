package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.repository.TemplateQuotaRepository;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import com.launchcatch.campaign.template.repository.TemplateVersionRepository;
import com.launchcatch.ops.contract.AuditLogWriter;
import com.launchcatch.global.entity.BaseTimeEntity;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TemplateDeleteServiceTest {

    private static final Long TEMPLATE_ID = 1L;
    private static final Long ADMIN_ID = 7L;

    @Mock
    private TemplateRepository templateRepository;
    @Mock
    private TemplateVersionRepository templateVersionRepository;
    @Mock
    private TemplateQuotaRepository templateQuotaRepository;
    @Mock
    private AuditLogWriter auditLogWriter;

    private TemplateDeleteService service;

    @BeforeEach
    void setUp() {
        service = new TemplateDeleteService(
                templateRepository, templateVersionRepository, templateQuotaRepository, auditLogWriter);
    }

    @Test
    @DisplayName("DRAFT 템플릿을 삭제하면 버전과 템플릿이 지워지고 생성 가능 수가 돌아간다")
    void DRAFT_템플릿을_삭제하면_버전과_템플릿이_지워지고_수가_돌아간다() throws Exception {
        Template template = draftTemplate();
        when(templateRepository.findByIdForUpdate(TEMPLATE_ID)).thenReturn(Optional.of(template));

        service.delete(ADMIN_ID, TEMPLATE_ID);

        verify(templateVersionRepository).deleteAllByTemplateId(TEMPLATE_ID);
        verify(templateRepository).delete(template);
        verify(templateQuotaRepository).release();
    }

    @Test
    @DisplayName("삭제하면 누가 어느 템플릿을 지웠는지 감사 로그에 남긴다(#877)")
    void 삭제하면_감사_로그를_남긴다() throws Exception {
        Template template = draftTemplate();
        when(templateRepository.findByIdForUpdate(TEMPLATE_ID)).thenReturn(Optional.of(template));

        service.delete(ADMIN_ID, TEMPLATE_ID);

        verify(auditLogWriter).write(ADMIN_ID, "TEMPLATE_DELETE", "1", "name=가을 신메뉴");
    }

    @Test
    @DisplayName("템플릿이 없으면 POSTER-005로 거부한다")
    void 템플릿이_없으면_거부한다() {
        when(templateRepository.findByIdForUpdate(TEMPLATE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(ADMIN_ID, TEMPLATE_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_NOT_FOUND));
        verify(templateVersionRepository, never()).deleteAllByTemplateId(TEMPLATE_ID);
        verify(templateQuotaRepository, never()).release();
    }

    @Test
    @DisplayName("게시된 템플릿은 POSTER-006으로 거부하고 아무것도 지우지 않는다")
    void 게시된_템플릿은_거부하고_아무것도_지우지_않는다() throws Exception {
        Template template = draftTemplate();
        template.publish("<div>1</div>", LocalDateTime.now(), 1);
        when(templateRepository.findByIdForUpdate(TEMPLATE_ID)).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> service.delete(ADMIN_ID, TEMPLATE_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_NOT_DRAFT));
        verify(templateVersionRepository, never()).deleteAllByTemplateId(TEMPLATE_ID);
        verify(templateRepository, never()).delete(template);
        verify(templateQuotaRepository, never()).release();
    }

    /*
     * 동시성 테스트는 실제 스레드 경쟁을 만들지 않고 findByIdForUpdate 호출 여부까지만
     * 확인한다(poster-api-design.md "템플릿 게시·활성화 관리" 절과 같은 기존 관례).
     */
    @Test
    @DisplayName("삭제는 잠금(findByIdForUpdate)으로 조회한다")
    void 삭제는_잠금으로_조회한다() throws Exception {
        Template template = draftTemplate();
        when(templateRepository.findByIdForUpdate(TEMPLATE_ID)).thenReturn(Optional.of(template));

        service.delete(ADMIN_ID, TEMPLATE_ID);

        verify(templateRepository).findByIdForUpdate(TEMPLATE_ID);
    }

    private Template draftTemplate() throws Exception {
        Template template = Template.createDraft("가을 신메뉴");
        setId(template, TEMPLATE_ID);
        template.addDraftVersion(1L, LocalDateTime.now(), "요청", "req-1", "<div>1</div>", 1);
        return template;
    }

    private void setId(Object entity, Long id) throws Exception {
        Field field = BaseTimeEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
