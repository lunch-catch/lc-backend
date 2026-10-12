package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.client.TemplateGenerationTimeoutException;
import com.launchcatch.campaign.template.client.TemplateHtmlGenerator;
import com.launchcatch.campaign.template.dto.TemplateCreateResponse;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.entity.TemplateStatus;
import com.launchcatch.campaign.template.entity.TemplateVersion;
import com.launchcatch.campaign.template.repository.TemplateQuotaRepository;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class TemplateCreateServiceTest {

    private static final String VALID_HTML = "<div style=\"color:#000000\">"
            + "<span data-slot=\"adLabel\">ad</span>"
            + "<h1 data-slot=\"eventName\">title</h1>"
            + "<p data-slot=\"discount\">discount</p>"
            + "<p data-slot=\"period\">period</p>"
            + "<img data-slot=\"image\" alt=\"menu\">"
            + "</div>";

    private static final Long ADMIN_ID = 1L;
    private static final String REQUEST_ID = "req-1";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-08T01:00:00Z"), ZoneOffset.UTC);

    @Mock
    private TemplateRepository templateRepository;
    @Mock
    private TemplateVersionRepository templateVersionRepository;
    @Mock
    private TemplateQuotaRepository templateQuotaRepository;
    @Mock
    private TemplateHtmlGenerator templateHtmlGenerator;
    @Mock
    private TransactionTemplate transactionTemplate;

    private TemplateCreateService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        service = new TemplateCreateService(
                templateRepository,
                templateVersionRepository,
                templateQuotaRepository,
                templateHtmlGenerator,
                new TemplateHtmlSanitizer(),
                new TemplateSlotValidator(),
                transactionTemplate,
                FIXED_CLOCK);
        lenient().when(templateVersionRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.empty());
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    @DisplayName("검증을 통과하면 임시저장 템플릿과 1번 버전을 저장하고 제거 내용을 돌려준다")
    void 검증을_통과하면_저장한다() throws Exception {
        when(templateRepository.count()).thenReturn(9L);
        when(templateQuotaRepository.tryReserve(TemplateCreateService.MAX_TEMPLATES)).thenReturn(1);
        when(templateHtmlGenerator.generate("가을 느낌")).thenReturn(VALID_HTML + "<script>alert(1)</script>");
        when(templateRepository.saveAndFlush(any(Template.class))).thenAnswer(invocation -> {
            Template saved = invocation.getArgument(0);
            setId(saved, 1L);
            return saved;
        });

        TemplateCreateResponse response = service.create("가을 신메뉴", "가을 느낌", REQUEST_ID, ADMIN_ID);

        assertThat(response.templateId()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("가을 신메뉴");
        assertThat(response.versionNumber()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(TemplateStatus.DRAFT);
        assertThat(response.html()).isEqualTo(VALID_HTML);
        assertThat(response.removedElements()).containsExactly("script");
        ArgumentCaptor<Template> captor = ArgumentCaptor.forClass(Template.class);
        verify(templateRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
        assertThat(captor.getValue().getHtmlContent()).isNull();
        assertThat(captor.getValue().getLastModifiedBy()).isEqualTo(ADMIN_ID);
        assertThat(captor.getValue().getLastModifiedAt()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));
    }

    @Test
    @DisplayName("같은 requestId 로 다시 요청하면 LLM 을 부르지 않고 그때 만든 버전으로 응답한다")
    void 같은_requestId_는_재시도로_처리한다() throws Exception {
        TemplateVersion existing = existingVersion(1L);
        when(templateVersionRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(existing));

        TemplateCreateResponse response = service.create("가을 신메뉴", "가을 느낌", REQUEST_ID, ADMIN_ID);

        assertThat(response.templateId()).isEqualTo(1L);
        assertThat(response.versionNumber()).isEqualTo(1);
        assertThat(response.html()).isEqualTo(VALID_HTML);
        assertThat(response.removedElements()).isEmpty();
        verifyNoInteractions(templateRepository, templateHtmlGenerator, templateQuotaRepository, transactionTemplate);
    }

    @Test
    @DisplayName("저장 시점에 같은 requestId 가 먼저 들어와 있으면 그 버전으로 응답한다")
    void 저장_시점_requestId_경쟁은_먼저_저장된_버전으로_응답한다() throws Exception {
        TemplateVersion existing = existingVersion(1L);
        when(templateVersionRepository.findByRequestId(REQUEST_ID))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(templateRepository.count()).thenReturn(0L);
        when(templateQuotaRepository.tryReserve(TemplateCreateService.MAX_TEMPLATES)).thenReturn(1);
        when(templateHtmlGenerator.generate("가을 느낌")).thenReturn(VALID_HTML);
        when(templateRepository.saveAndFlush(any(Template.class)))
                .thenThrow(new DataIntegrityViolationException("uk_template_version_request_id"));

        TemplateCreateResponse response = service.create("가을 신메뉴", "가을 느낌", REQUEST_ID, ADMIN_ID);

        assertThat(response.templateId()).isEqualTo(1L);
        assertThat(response.versionNumber()).isEqualTo(1);
        assertThat(response.removedElements()).isEmpty();
    }

    @Test
    @DisplayName("저장 시점의 제약 위반이 requestId 충돌이 아니면 그 예외를 그대로 던진다")
    void 저장_시점_제약_위반이_requestId_충돌이_아니면_그대로_던진다() {
        when(templateRepository.count()).thenReturn(0L);
        when(templateQuotaRepository.tryReserve(TemplateCreateService.MAX_TEMPLATES)).thenReturn(1);
        when(templateHtmlGenerator.generate("가을 느낌")).thenReturn(VALID_HTML);
        DataIntegrityViolationException violation = new DataIntegrityViolationException("다른 제약 위반");
        when(templateRepository.saveAndFlush(any(Template.class))).thenThrow(violation);

        assertThatThrownBy(() -> service.create("가을 신메뉴", "가을 느낌", REQUEST_ID, ADMIN_ID))
                .isSameAs(violation);
    }

    @Test
    @DisplayName("템플릿이 이미 10개면 LLM 을 부르지 않고 POSTER-003 으로 거부한다")
    void 템플릿이_10개면_거부한다() {
        when(templateRepository.count()).thenReturn(10L);

        assertThatThrownBy(() -> service.create("가을 신메뉴", "가을 느낌", REQUEST_ID, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_LIMIT_EXCEEDED));
        verifyNoInteractions(templateHtmlGenerator);
        verifyNoInteractions(templateQuotaRepository);
        verify(templateRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("초기 확인을 통과해도 저장 시점에 자리를 못 받으면 거부한다")
    void 저장_시점에_자리를_못_받으면_거부한다() {
        when(templateRepository.count()).thenReturn(9L);
        when(templateQuotaRepository.tryReserve(TemplateCreateService.MAX_TEMPLATES)).thenReturn(0);
        when(templateHtmlGenerator.generate("가을 느낌")).thenReturn(VALID_HTML);

        assertThatThrownBy(() -> service.create("가을 신메뉴", "가을 느낌", REQUEST_ID, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_LIMIT_EXCEEDED));
        verify(templateRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("LLM 응답이 시간 초과되면 POSTER-004 이고 저장하지 않는다")
    void LLM_시간_초과는_503이다() {
        when(templateRepository.count()).thenReturn(0L);
        TemplateGenerationTimeoutException timeout = new TemplateGenerationTimeoutException("30초 초과");
        when(templateHtmlGenerator.generate("가을 느낌")).thenThrow(timeout);

        assertThatThrownBy(() -> service.create("가을 신메뉴", "가을 느낌", REQUEST_ID, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.GENERATION_TIMEOUT);
                    assertThat(e.getCause()).isSameAs(timeout);
                });
        verify(templateRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("슬롯이 빠진 HTML 은 POSTER-001 이고 저장하지 않는다")
    void 슬롯이_빠지면_저장하지_않는다() {
        when(templateRepository.count()).thenReturn(0L);
        when(templateHtmlGenerator.generate("가을 느낌")).thenReturn("<div data-slot=\"eventName\">title</div>");

        assertThatThrownBy(() -> service.create("가을 신메뉴", "가을 느낌", REQUEST_ID, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.SLOT_CONTRACT_VIOLATION));
        verifyNoInteractions(transactionTemplate);
        verify(templateRepository, never()).saveAndFlush(any());
    }

    private TemplateVersion existingVersion(Long templateId) throws Exception {
        Template template = Template.createDraft("가을 신메뉴");
        setId(template, templateId);
        return template.addDraftVersion(ADMIN_ID, LocalDateTime.now(FIXED_CLOCK), "가을 느낌", REQUEST_ID, VALID_HTML, 1);
    }

    private void setId(Object entity, Long id) throws Exception {
        Field field = BaseTimeEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
