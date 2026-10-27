package com.lunchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.campaign.exception.CampaignException;
import com.lunchcatch.campaign.exception.PosterErrorCode;
import com.lunchcatch.campaign.template.client.TemplateGenerationTimeoutException;
import com.lunchcatch.campaign.template.client.GeneratedTemplate;
import com.lunchcatch.campaign.template.client.TemplateHtmlGenerator;
import com.lunchcatch.campaign.template.dto.TemplateCreateResponse;
import com.lunchcatch.campaign.template.entity.Template;
import com.lunchcatch.campaign.template.entity.TemplateStatus;
import com.lunchcatch.campaign.template.entity.TemplateVersion;
import com.lunchcatch.campaign.template.repository.TemplateQuotaRepository;
import com.lunchcatch.campaign.template.repository.TemplateRepository;
import com.lunchcatch.campaign.template.repository.TemplateVersionRepository;
import com.lunchcatch.campaign.template.TemplateHtmlSanitizer;
import com.lunchcatch.campaign.template.TemplatePromptValidator;
import com.lunchcatch.campaign.template.TemplateSlotValidator;
import com.lunchcatch.global.entity.BaseTimeEntity;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class TemplateCreateServiceTest {

    private static final String VALID_HTML = "<div style=\"color:#000000\">"
            + "<h1 data-slot=\"eventName\">title</h1>"
            + "<p data-slot=\"discount\">discount</p>"
            + "<p data-slot=\"period\">period</p>"
            + "<img data-slot=\"image\" alt=\"menu\">"
            + "</div>";

    private static final Long ADMIN_ID = 1L;
    private static final String REQUEST_ID = "req-1";

    private static final List<String> GENERATED_SUGGESTIONS =
            List.of("행사명 글자를 조금 더 크게 해줘", "배경을 조금 더 밝게 해줘");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-08T01:00:00Z"), ZoneOffset.UTC);
    // 서비스에 직접 넣는 테스트용 값이다. application.yml의 campaign.template-max-count와는 무관하다.
    private static final int MAX_TEMPLATES = 10;

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
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private TemplateCreateService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        service = new TemplateCreateService(
                templateRepository,
                templateVersionRepository,
                templateQuotaRepository,
                templateHtmlGenerator,
                new TemplateHtmlSanitizer(),
                new TemplateSlotValidator(),
                new TemplatePromptValidator(),
                new TemplateGenerationRateLimiter(redisTemplate, 3),
                transactionTemplate,
                FIXED_CLOCK);
        Field maxTemplatesField = TemplateCreateService.class.getDeclaredField("maxTemplates");
        maxTemplatesField.setAccessible(true);
        maxTemplatesField.setInt(service, MAX_TEMPLATES);
        lenient().when(templateVersionRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.empty());
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    /*
     * checkLimit 이 먼저 거치는 in-flight 표시를 "처음 보는 requestId" 로 통과시킨다.
     * 재시도 테스트(같은_requestId_는_재시도로_처리한다)는 checkLimit 자체가 호출되지
     * 않아야 해서 이 스텁을 쓰지 않는다. setUp 에 넣으면 그 테스트의 redisTemplate
     * verifyNoInteractions 가 스텁 호출 자체를 상호작용으로 잡아 깨진다.
     */
    private void 처음_보는_requestId로_설정한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    }

    @Test
    @DisplayName("검증을 통과하면 임시저장 템플릿과 1번 버전을 저장하고 제거 내용을 돌려준다")
    void 검증을_통과하면_저장한다() throws Exception {
        처음_보는_requestId로_설정한다();
        when(templateRepository.count()).thenReturn(9L);
        when(templateQuotaRepository.tryReserve(MAX_TEMPLATES)).thenReturn(1);
        when(templateHtmlGenerator.generate("가을 느낌")).thenReturn(new GeneratedTemplate(VALID_HTML + "<script>alert(1)</script>", "", GENERATED_SUGGESTIONS));
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
        assertThat(response.suggestedPrompts()).containsExactlyElementsOf(GENERATED_SUGGESTIONS);
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
        // 재시도 응답은 저장된 버전에서 만들어지고 제안은 저장하지 않으므로 비어 있다(#732)
        assertThat(response.suggestedPrompts()).isEmpty();
        verifyNoInteractions(
                templateRepository, templateHtmlGenerator, templateQuotaRepository, transactionTemplate,
                redisTemplate);
    }

    @Test
    @DisplayName("분당 3회를 넘으면 LLM 을 부르지 않고 POSTER-014 으로 거부한다")
    void 속도_제한을_넘으면_거부한다() {
        처음_보는_requestId로_설정한다();
        when(redisTemplate.execute(any(), any(), any())).thenReturn(4L);

        assertThatThrownBy(() -> service.create("가을 신메뉴", "가을 느낌", REQUEST_ID, ADMIN_ID))
                .isInstanceOf(CampaignException.class)
                .extracting(e -> ((CampaignException) e).getErrorCode())
                .isEqualTo(PosterErrorCode.GENERATION_RATE_LIMITED);
        verifyNoInteractions(templateHtmlGenerator);
    }

    @Test
    @DisplayName("저장되기 전에 같은 requestId 가 다시 들어오면 POSTER-004 로 거부하고 LLM 을 또 부르지 않는다")
    void 저장되기_전_같은_requestId_재진입은_POSTER_004로_거부한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> service.create("가을 신메뉴", "가을 느낌", REQUEST_ID, ADMIN_ID))
                .isInstanceOf(CampaignException.class)
                .extracting(e -> ((CampaignException) e).getErrorCode())
                .isEqualTo(PosterErrorCode.GENERATION_TIMEOUT);

        verifyNoInteractions(templateHtmlGenerator);
        verify(templateRepository, never()).saveAndFlush(any());
        verify(redisTemplate, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("저장 시점에 같은 requestId 가 먼저 들어와 있으면 그 버전으로 응답한다")
    void 저장_시점_requestId_경쟁은_먼저_저장된_버전으로_응답한다() throws Exception {
        처음_보는_requestId로_설정한다();
        TemplateVersion existing = existingVersion(1L);
        when(templateVersionRepository.findByRequestId(REQUEST_ID))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(templateRepository.count()).thenReturn(0L);
        when(templateQuotaRepository.tryReserve(MAX_TEMPLATES)).thenReturn(1);
        when(templateHtmlGenerator.generate("가을 느낌")).thenReturn(new GeneratedTemplate(VALID_HTML, ""));
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
        처음_보는_requestId로_설정한다();
        when(templateRepository.count()).thenReturn(0L);
        when(templateQuotaRepository.tryReserve(MAX_TEMPLATES)).thenReturn(1);
        when(templateHtmlGenerator.generate("가을 느낌")).thenReturn(new GeneratedTemplate(VALID_HTML, ""));
        DataIntegrityViolationException violation = new DataIntegrityViolationException("다른 제약 위반");
        when(templateRepository.saveAndFlush(any(Template.class))).thenThrow(violation);

        assertThatThrownBy(() -> service.create("가을 신메뉴", "가을 느낌", REQUEST_ID, ADMIN_ID))
                .isSameAs(violation);
    }

    @Test
    @DisplayName("요청 문구가 검증에 걸리면 LLM 을 부르지 않고 사유 코드로 거부하며 처리 중 표식을 푼다(#428)")
    void 요청_문구가_검증에_걸리면_LLM을_부르지_않는다() {
        처음_보는_requestId로_설정한다();

        assertThatThrownBy(() -> service.create("가을 신메뉴", "ㅁ", REQUEST_ID, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.PROMPT_TOO_SHORT));

        verifyNoInteractions(templateHtmlGenerator);
        verify(redisTemplate).delete("templateGenerationInFlight:" + REQUEST_ID);
    }

    @Test
    @DisplayName("템플릿 요청이 아니라 대화만 있는 응답이면 템플릿을 만들지 않고 message 만 돌려준다(#733)")
    void 대화만_있는_응답이면_템플릿을_만들지_않는다() {
        처음_보는_requestId로_설정한다();
        when(templateRepository.count()).thenReturn(9L);
        when(templateHtmlGenerator.generate("이 도구는 뭘 할 수 있어?"))
                .thenReturn(GeneratedTemplate.chatOnly("템플릿의 색과 배치를 요청하시면 만들어 드려요."));

        TemplateCreateResponse response = service.create("가을 신메뉴", "이 도구는 뭘 할 수 있어?", REQUEST_ID, ADMIN_ID);

        assertThat(response.templateId()).isNull();
        assertThat(response.versionNumber()).isNull();
        assertThat(response.html()).isNull();
        assertThat(response.message()).isEqualTo("템플릿의 색과 배치를 요청하시면 만들어 드려요.");
        assertThat(response.suggestedPrompts()).isEmpty();
        verify(templateRepository, never()).saveAndFlush(any());
        verifyNoInteractions(templateQuotaRepository);
        verify(redisTemplate).delete("templateGenerationInFlight:" + REQUEST_ID);
    }

    @Test
    @DisplayName("템플릿이 이미 10개면 LLM 을 부르지 않고 POSTER-003 으로 거부한다")
    void 템플릿이_10개면_거부한다() {
        처음_보는_requestId로_설정한다();
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
        처음_보는_requestId로_설정한다();
        when(templateRepository.count()).thenReturn(9L);
        when(templateQuotaRepository.tryReserve(MAX_TEMPLATES)).thenReturn(0);
        when(templateHtmlGenerator.generate("가을 느낌")).thenReturn(new GeneratedTemplate(VALID_HTML, ""));

        assertThatThrownBy(() -> service.create("가을 신메뉴", "가을 느낌", REQUEST_ID, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_LIMIT_EXCEEDED));
        verify(templateRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("LLM 응답이 시간 초과되면 POSTER-004 이고 저장하지 않는다")
    void LLM_시간_초과는_503이다() {
        처음_보는_requestId로_설정한다();
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
        처음_보는_requestId로_설정한다();
        when(templateRepository.count()).thenReturn(0L);
        when(templateHtmlGenerator.generate("가을 느낌")).thenReturn(new GeneratedTemplate("<div data-slot=\"eventName\">title</div>", ""));

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
