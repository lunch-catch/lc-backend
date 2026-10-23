package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.dto.TemplatePublishResponse;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.entity.TemplateStatus;
import com.launchcatch.campaign.template.entity.TemplateVersion;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import com.launchcatch.campaign.template.repository.TemplateVersionRepository;
import com.launchcatch.global.entity.BaseTimeEntity;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TemplatePublishServiceTest {

    private static final Long TEMPLATE_ID = 1L;
    private static final Long ADMIN_ID = 7L;
    private static final int VERSION_NUMBER = 1;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-12T01:00:00Z"), ZoneOffset.UTC);

    @Mock
    private TemplateRepository templateRepository;
    @Mock
    private TemplateVersionRepository templateVersionRepository;

    private TemplatePublishService service;

    @BeforeEach
    void setUp() {
        service = new TemplatePublishService(templateRepository, templateVersionRepository, FIXED_CLOCK);
    }

    @Test
    @DisplayName("DRAFT 템플릿을 게시하면 선택한 버전의 HTML로 확정되고 모든 버전이 소프트삭제된다")
    void 게시하면_확정되고_버전이_소프트삭제된다() throws Exception {
        Template template = draftTemplate();
        when(templateRepository.findByIdForUpdate(TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(templateVersionRepository
                .findByTemplateIdAndVersionNumberAndDeletedAtIsNull(TEMPLATE_ID, VERSION_NUMBER))
                .thenReturn(Optional.of(template.latestVersion()));

        TemplatePublishResponse response = service.publish(TEMPLATE_ID, VERSION_NUMBER, ADMIN_ID);

        assertThat(response.templateId()).isEqualTo(TEMPLATE_ID);
        assertThat(response.status()).isEqualTo(TemplateStatus.PUBLISHED);
        assertThat(response.html()).isEqualTo("<div>1</div>");
        assertThat(response.publishedAt()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));
        verify(templateRepository).findByIdForUpdate(TEMPLATE_ID);
        verify(templateVersionRepository)
                .softDeleteAllByTemplateId(TEMPLATE_ID, LocalDateTime.now(FIXED_CLOCK));
    }

    @Test
    @DisplayName("템플릿이 없으면 POSTER-005 로 거부한다")
    void 템플릿이_없으면_거부한다() {
        when(templateRepository.findByIdForUpdate(TEMPLATE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.publish(TEMPLATE_ID, VERSION_NUMBER, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_NOT_FOUND));
    }

    @Test
    @DisplayName("다른 버전으로 이미 게시돼 있으면 POSTER-007 로 거부한다")
    void 다른_버전으로_게시돼_있으면_거부한다() throws Exception {
        Template template = draftTemplate();
        template.publish("<div>other</div>", LocalDateTime.now(FIXED_CLOCK), 2);
        when(templateRepository.findByIdForUpdate(TEMPLATE_ID)).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> service.publish(TEMPLATE_ID, VERSION_NUMBER, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_ALREADY_PUBLISHED));
        verify(templateVersionRepository, never()).softDeleteAllByTemplateId(any(), any());
    }

    @Test
    @DisplayName("같은 버전으로 이미 게시돼 있으면 재시도로 보고 같은 응답을 돌려준다")
    void 같은_버전으로_다시_게시_요청하면_재시도로_처리한다() throws Exception {
        Template template = draftTemplate();
        template.publish("<div>1</div>", LocalDateTime.now(FIXED_CLOCK), VERSION_NUMBER);
        when(templateRepository.findByIdForUpdate(TEMPLATE_ID)).thenReturn(Optional.of(template));

        TemplatePublishResponse response = service.publish(TEMPLATE_ID, VERSION_NUMBER, ADMIN_ID);

        assertThat(response.templateId()).isEqualTo(TEMPLATE_ID);
        assertThat(response.status()).isEqualTo(TemplateStatus.PUBLISHED);
        assertThat(response.html()).isEqualTo("<div>1</div>");
        verify(templateVersionRepository, never()).softDeleteAllByTemplateId(any(), any());
    }

    @Test
    @DisplayName("게시된 템플릿에 임시저장이 있으면 그 버전으로 재게시하고 active 는 그대로 둔다(#880)")
    void 게시된_템플릿을_임시저장_버전으로_재게시한다() throws Exception {
        Template template = publishedActiveTemplate();
        TemplateVersion draft = template.addDraftVersion(
                3L, LocalDateTime.now(FIXED_CLOCK).minusHours(1), "요청", "req-2", "<div>2</div>", 2);
        when(templateRepository.findByIdForUpdate(TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(templateVersionRepository.findByTemplateIdAndVersionNumberAndDeletedAtIsNull(TEMPLATE_ID, 2))
                .thenReturn(Optional.of(draft));

        TemplatePublishResponse response = service.publish(TEMPLATE_ID, 2, ADMIN_ID);

        assertThat(response.html()).isEqualTo("<div>2</div>");
        assertThat(response.publishedAt()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));
        assertThat(template.getHtmlContent()).isEqualTo("<div>2</div>");
        assertThat(template.getPublishedVersionNumber()).isEqualTo(2);
        assertThat(template.getLastModifiedBy()).isEqualTo(ADMIN_ID);
        assertThat(template.getLastModifiedAt()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));
        assertThat(template.isActive()).isTrue();
        verify(templateVersionRepository)
                .softDeleteAllByTemplateId(TEMPLATE_ID, LocalDateTime.now(FIXED_CLOCK));
    }

    @Test
    @DisplayName("재게시 응답을 못 받아 같은 번호로 다시 요청하면 재시도로 보고 지금 상태를 돌려준다(#880)")
    void 재게시를_같은_번호로_다시_요청하면_재시도로_처리한다() throws Exception {
        Template template = publishedActiveTemplate();
        template.republish("<div>2</div>", LocalDateTime.now(FIXED_CLOCK), 2, ADMIN_ID);
        when(templateRepository.findByIdForUpdate(TEMPLATE_ID)).thenReturn(Optional.of(template));

        TemplatePublishResponse response = service.publish(TEMPLATE_ID, 2, ADMIN_ID);

        assertThat(response.html()).isEqualTo("<div>2</div>");
        verify(templateVersionRepository, never()).softDeleteAllByTemplateId(any(), any());
    }

    @Test
    @DisplayName("게시된 템플릿에 임시저장은 있는데 고른 번호가 없으면 POSTER-008 이다(#880)")
    void 재게시할_번호가_임시저장에_없으면_거부한다() throws Exception {
        Template template = publishedActiveTemplate();
        when(templateRepository.findByIdForUpdate(TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(templateVersionRepository.existsByTemplateIdAndDeletedAtIsNull(TEMPLATE_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.publish(TEMPLATE_ID, 99, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.VERSION_NOT_FOUND));
        verify(templateVersionRepository, never()).softDeleteAllByTemplateId(any(), any());
    }

    @Test
    @DisplayName("존재하지 않는 버전 번호면 POSTER-008 로 거부한다")
    void 존재하지_않는_버전이면_거부한다() throws Exception {
        Template template = draftTemplate();
        when(templateRepository.findByIdForUpdate(TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(templateVersionRepository.findByTemplateIdAndVersionNumberAndDeletedAtIsNull(TEMPLATE_ID, 99))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.publish(TEMPLATE_ID, 99, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.VERSION_NOT_FOUND));
        verify(templateVersionRepository, never()).softDeleteAllByTemplateId(any(), any());
    }

    private Template publishedActiveTemplate() throws Exception {
        Template template = draftTemplate();
        template.publish("<div>1</div>", LocalDateTime.now(FIXED_CLOCK).minusDays(1), VERSION_NUMBER);
        template.changeActive(true, 1L, LocalDateTime.now(FIXED_CLOCK).minusDays(1));
        return template;
    }

    private Template draftTemplate() throws Exception {
        Template template = Template.createDraft("가을 신메뉴");
        setId(template, TEMPLATE_ID);
        template.addDraftVersion(1L, LocalDateTime.now(FIXED_CLOCK), "요청", "req-1", "<div>1</div>", VERSION_NUMBER);
        return template;
    }

    private void setId(Object entity, Long id) throws Exception {
        Field field = BaseTimeEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
