package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.client.TemplateGenerationTimeoutException;
import com.launchcatch.campaign.template.client.TemplateHtmlGenerator;
import com.launchcatch.campaign.template.dto.TemplateCreateResponse;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class TemplateReviseServiceTest {

    private static final String PREVIOUS_HTML = "<div>"
            + "<span data-slot=\"adLabel\">ad</span>"
            + "<h1 data-slot=\"eventName\">title</h1>"
            + "<p data-slot=\"discount\">discount</p>"
            + "<p data-slot=\"period\">period</p>"
            + "<img data-slot=\"image\" alt=\"menu\">"
            + "</div>";

    private static final Long ADMIN_ID = 1L;
    private static final Long TEMPLATE_ID = 1L;
    private static final String REQUEST_ID = "req-1";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-08T01:00:00Z"), ZoneOffset.UTC);

    @Mock
    private TemplateRepository templateRepository;
    @Mock
    private TemplateVersionRepository templateVersionRepository;
    @Mock
    private TemplateHtmlGenerator templateHtmlGenerator;
    @Mock
    private TransactionTemplate transactionTemplate;

    private TemplateReviseService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        service = new TemplateReviseService(
                templateRepository,
                templateVersionRepository,
                templateHtmlGenerator,
                new TemplateHtmlSanitizer(),
                new TemplateSlotValidator(),
                new TemplateStructureValidator(),
                transactionTemplate,
                FIXED_CLOCK);
        lenient().when(templateVersionRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.empty());
        lenient().when(templateVersionRepository.findMaxVersionNumber(TEMPLATE_ID)).thenReturn(1);
        lenient().when(templateRepository.findById(TEMPLATE_ID)).thenAnswer(invocation -> Optional.of(draftTemplate()));
        lenient().when(templateVersionRepository.findFirstByTemplateIdOrderByVersionNumberDesc(TEMPLATE_ID))
                .thenAnswer(invocation -> Optional.of(draftTemplate().latestVersion()));
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    @DisplayName("검증을 통과하면 새 버전을 저장하고 제거 내용을 돌려준다")
    void 검증을_통과하면_저장한다() throws Exception {
        when(templateRepository.findByIdWithVersions(TEMPLATE_ID))
                .thenReturn(Optional.of(draftTemplate()));
        when(templateHtmlGenerator.revise(PREVIOUS_HTML, "버튼 색 바꿔줘"))
                .thenReturn(PREVIOUS_HTML.replace(
                        "<h1 data-slot=\"eventName\">title</h1>", "<h1 data-slot=\"eventName\">새 제목</h1>")
                        + "<script>alert(1)</script>");
        when(templateRepository.saveAndFlush(any(Template.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TemplateCreateResponse response = service.revise(TEMPLATE_ID, "버튼 색 바꿔줘", REQUEST_ID, ADMIN_ID);

        assertThat(response.templateId()).isEqualTo(TEMPLATE_ID);
        assertThat(response.versionNumber()).isEqualTo(2);
        assertThat(response.removedElements()).containsExactly("script");
    }

    @Test
    @DisplayName("같은 requestId 로 다시 요청하면 LLM 을 부르지 않고 그때 만든 버전으로 응답한다")
    void 같은_requestId_는_재시도로_처리한다() throws Exception {
        TemplateVersion existing = existingVersion();
        when(templateVersionRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(existing));

        TemplateCreateResponse response = service.revise(TEMPLATE_ID, "버튼 색 바꿔줘", REQUEST_ID, ADMIN_ID);

        assertThat(response.templateId()).isEqualTo(TEMPLATE_ID);
        assertThat(response.removedElements()).isEmpty();
        verify(templateRepository, never()).findByIdWithVersions(any());
        verify(templateHtmlGenerator, never()).revise(any(), any());
    }

    @Test
    @DisplayName("같은 requestId 가 다른 templateId 의 버전이면 거부한다")
    void requestId_로_찾은_버전이_다른_템플릿이면_거부한다() throws Exception {
        TemplateVersion existing = existingVersion();
        when(templateVersionRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(existing));
        Long otherTemplateId = 2L;

        assertThatThrownBy(() -> service.revise(otherTemplateId, "버튼 색 바꿔줘", REQUEST_ID, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_NOT_FOUND));
    }

    @Test
    @DisplayName("저장 시점에 같은 requestId 가 먼저 들어와 있으면 그 버전으로 응답한다")
    void 저장_시점_requestId_경쟁은_먼저_저장된_버전으로_응답한다() throws Exception {
        TemplateVersion existing = existingVersion();
        when(templateVersionRepository.findByRequestId(REQUEST_ID))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(templateRepository.findByIdWithVersions(TEMPLATE_ID))
                .thenReturn(Optional.of(draftTemplate()));
        when(templateHtmlGenerator.revise(PREVIOUS_HTML, "버튼 색 바꿔줘")).thenReturn(PREVIOUS_HTML);
        when(templateRepository.saveAndFlush(any(Template.class)))
                .thenThrow(new DataIntegrityViolationException("uk_template_version_request_id"));

        TemplateCreateResponse response = service.revise(TEMPLATE_ID, "버튼 색 바꿔줘", REQUEST_ID, ADMIN_ID);

        assertThat(response.templateId()).isEqualTo(TEMPLATE_ID);
        assertThat(response.removedElements()).isEmpty();
    }

    @Test
    @DisplayName("저장 시점의 제약 위반이 requestId 충돌이 아니면 그 예외를 그대로 던진다")
    void 저장_시점_제약_위반이_requestId_충돌이_아니면_그대로_던진다() throws Exception {
        when(templateRepository.findByIdWithVersions(TEMPLATE_ID))
                .thenReturn(Optional.of(draftTemplate()));
        when(templateHtmlGenerator.revise(PREVIOUS_HTML, "버튼 색 바꿔줘")).thenReturn(PREVIOUS_HTML);
        DataIntegrityViolationException violation = new DataIntegrityViolationException("다른 제약 위반");
        when(templateRepository.saveAndFlush(any(Template.class))).thenThrow(violation);

        assertThatThrownBy(() -> service.revise(TEMPLATE_ID, "버튼 색 바꿔줘", REQUEST_ID, ADMIN_ID))
                .isSameAs(violation);
    }

    @Test
    @DisplayName("템플릿이 없으면 POSTER-005 로 거부한다")
    void 템플릿이_없으면_거부한다() {
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revise(TEMPLATE_ID, "버튼 색 바꿔줘", REQUEST_ID, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_NOT_FOUND));
    }

    @Test
    @DisplayName("템플릿은 있는데 버전이 하나도 없으면(불변식 위반) 예외를 던진다")
    void 버전이_없으면_예외를_던진다() {
        when(templateVersionRepository.findFirstByTemplateIdOrderByVersionNumberDesc(TEMPLATE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revise(TEMPLATE_ID, "버튼 색 바꿔줘", REQUEST_ID, ADMIN_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("저장 시점에 템플릿이 이미 지워져 있으면 POSTER-005 로 거부한다")
    void 저장_시점에_템플릿이_없으면_거부한다() {
        when(templateRepository.findByIdWithVersions(TEMPLATE_ID)).thenReturn(Optional.empty());
        when(templateHtmlGenerator.revise(PREVIOUS_HTML, "버튼 색 바꿔줘")).thenReturn(PREVIOUS_HTML);

        assertThatThrownBy(() -> service.revise(TEMPLATE_ID, "버튼 색 바꿔줘", REQUEST_ID, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_NOT_FOUND));
    }

    @Test
    @DisplayName("게시된 템플릿은 POSTER-006 으로 거부한다")
    void 게시된_템플릿은_거부한다() throws Exception {
        Template template = draftTemplate();
        setStatus(template, TemplateStatus.PUBLISHED);
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> service.revise(TEMPLATE_ID, "버튼 색 바꿔줘", REQUEST_ID, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_NOT_DRAFT));
        verify(templateHtmlGenerator, never()).revise(any(), any());
    }

    @Test
    @DisplayName("LLM 응답이 시간 초과되면 POSTER-004 이고 저장하지 않는다")
    void LLM_시간_초과는_503이다() {
        TemplateGenerationTimeoutException timeout = new TemplateGenerationTimeoutException("30초 초과");
        when(templateHtmlGenerator.revise(PREVIOUS_HTML, "버튼 색 바꿔줘")).thenThrow(timeout);

        assertThatThrownBy(() -> service.revise(TEMPLATE_ID, "버튼 색 바꿔줘", REQUEST_ID, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.GENERATION_TIMEOUT);
                    assertThat(e.getCause()).isSameAs(timeout);
                });
        verify(templateRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("슬롯이 빠진 HTML 은 POSTER-001 이고 저장하지 않는다")
    void 슬롯이_빠지면_저장하지_않는다() {
        when(templateHtmlGenerator.revise(PREVIOUS_HTML, "버튼 색 바꿔줘"))
                .thenReturn("<div data-slot=\"eventName\">title</div>");

        assertThatThrownBy(() -> service.revise(TEMPLATE_ID, "버튼 색 바꿔줘", REQUEST_ID, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.SLOT_CONTRACT_VIOLATION));
        verify(templateRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("슬롯 구조가 바뀌면 POSTER-002 이고 저장하지 않는다")
    void 슬롯_구조가_바뀌면_저장하지_않는다() {
        when(templateHtmlGenerator.revise(PREVIOUS_HTML, "버튼 색 바꿔줘"))
                .thenReturn(PREVIOUS_HTML.replace(
                        "<h1 data-slot=\"eventName\">title</h1>", "<span data-slot=\"eventName\">title</span>"));

        assertThatThrownBy(() -> service.revise(TEMPLATE_ID, "버튼 색 바꿔줘", REQUEST_ID, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.STRUCTURE_CHANGED));
        verify(templateRepository, never()).saveAndFlush(any());
    }

    private Template draftTemplate() throws Exception {
        Template template = Template.createDraft("가을 신메뉴");
        setId(template, TEMPLATE_ID);
        template.addDraftVersion(ADMIN_ID, LocalDateTime.now(FIXED_CLOCK), "처음 요청", "req-0", PREVIOUS_HTML, 1);
        return template;
    }

    private TemplateVersion existingVersion() throws Exception {
        Template template = draftTemplate();
        return template.addDraftVersion(
                ADMIN_ID, LocalDateTime.now(FIXED_CLOCK), "버튼 색 바꿔줘", REQUEST_ID, PREVIOUS_HTML, 2);
    }

    private void setId(Object entity, Long id) throws Exception {
        Field field = BaseTimeEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private void setStatus(Template template, TemplateStatus status) throws Exception {
        Field field = Template.class.getDeclaredField("status");
        field.setAccessible(true);
        field.set(template, status);
    }
}
