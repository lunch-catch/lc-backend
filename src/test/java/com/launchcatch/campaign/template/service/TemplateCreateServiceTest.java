package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.client.TemplateGenerationTimeoutException;
import com.launchcatch.campaign.template.client.GeneratedTemplate;
import com.launchcatch.campaign.template.client.TemplateHtmlGenerator;
import com.launchcatch.campaign.template.dto.TemplateCreateResponse;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.entity.TemplateStatus;
import com.launchcatch.campaign.template.entity.TemplateVersion;
import com.launchcatch.campaign.template.repository.TemplateQuotaRepository;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import com.launchcatch.campaign.template.repository.TemplateVersionRepository;
import com.launchcatch.campaign.template.TemplateHtmlSanitizer;
import com.launchcatch.campaign.template.TemplateSlotValidator;
import com.launchcatch.global.entity.BaseTimeEntity;
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
            + "<span data-slot=\"adLabel\">ad</span>"
            + "<h1 data-slot=\"eventName\">title</h1>"
            + "<p data-slot=\"discount\">discount</p>"
            + "<p data-slot=\"period\">period</p>"
            + "<img data-slot=\"image\" alt=\"menu\">"
            + "</div>";

    private static final Long ADMIN_ID = 1L;
    private static final String REQUEST_ID = "req-1";

    // 상수를 참조하지 않고 poster-api-design.md 의 문장을 그대로 적어야 목록이 바뀐 것을 잡는다.
    private static final List<String> EXPECTED_SUGGESTED_PROMPTS = List.of(
            "배경을 더 밝게 해줘",
            "행사명 글자를 더 크게 해줘",
            "할인 문구를 더 진한 강조색으로 바꿔줘",
            "행사명을 중앙에서 오른쪽으로 1cm 옮겨줘",
            "이미지 모서리를 더 둥글게 해줘");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-08T01:00:00Z"), ZoneOffset.UTC);
    // 실제 설계값(V51)이다. application.yml의 campaign.template-max-count는 임시 상향값(#529)이라 이 테스트와 무관하다.
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
                new TemplateGenerationRateLimiter(redisTemplate),
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
        when(templateHtmlGenerator.generate("가을 느낌")).thenReturn(new GeneratedTemplate(VALID_HTML + "<script>alert(1)</script>", ""));
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
        assertThat(response.suggestedPrompts()).containsExactlyElementsOf(EXPECTED_SUGGESTED_PROMPTS);
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
        assertThat(response.suggestedPrompts()).containsExactlyElementsOf(EXPECTED_SUGGESTED_PROMPTS);
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
