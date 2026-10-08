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
import com.launchcatch.campaign.template.repository.TemplateRepository;
import com.launchcatch.global.entity.BaseTimeEntity;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-08T01:00:00Z"), ZoneOffset.UTC);

    @Mock
    private TemplateRepository templateRepository;
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
                templateHtmlGenerator,
                new TemplateHtmlSanitizer(),
                new TemplateSlotValidator(),
                new TemplatePaletteValidator(),
                transactionTemplate,
                FIXED_CLOCK);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    @DisplayName("검증을 통과하면 임시저장 템플릿과 1번 버전을 저장하고 제거 내용을 돌려준다")
    void 검증을_통과하면_저장한다() throws Exception {
        when(templateRepository.count()).thenReturn(9L);
        when(templateHtmlGenerator.generate("가을 느낌")).thenReturn(VALID_HTML + "<script>alert(1)</script>");
        when(templateRepository.save(any(Template.class))).thenAnswer(invocation -> {
            Template saved = invocation.getArgument(0);
            setId(saved, 1L);
            return saved;
        });

        TemplateCreateResponse response = service.create("가을 신메뉴", "가을 느낌", ADMIN_ID);

        assertThat(response.templateId()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("가을 신메뉴");
        assertThat(response.versionNumber()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(TemplateStatus.DRAFT);
        assertThat(response.html()).isEqualTo(VALID_HTML);
        assertThat(response.removedElements()).containsExactly("script");
        ArgumentCaptor<Template> captor = ArgumentCaptor.forClass(Template.class);
        verify(templateRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
        assertThat(captor.getValue().getHtmlContent()).isNull();
        assertThat(captor.getValue().getLastModifiedBy()).isEqualTo(ADMIN_ID);
        assertThat(captor.getValue().getLastModifiedAt()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));
    }

    @Test
    @DisplayName("템플릿이 이미 10개면 LLM 을 부르지 않고 POSTER-004 로 거부한다")
    void 템플릿이_10개면_거부한다() {
        when(templateRepository.count()).thenReturn(10L);

        assertThatThrownBy(() -> service.create("가을 신메뉴", "가을 느낌", ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_LIMIT_EXCEEDED));
        verifyNoInteractions(templateHtmlGenerator);
        verify(templateRepository, never()).save(any());
    }

    @Test
    @DisplayName("초기 확인 뒤 LLM 호출 중 다른 요청이 먼저 채워 10개가 되면 저장 직전에 거부한다")
    void 저장_직전_재확인에서_10개가_되면_거부한다() {
        when(templateRepository.count()).thenReturn(9L, 10L);
        when(templateHtmlGenerator.generate("가을 느낌")).thenReturn(VALID_HTML);

        assertThatThrownBy(() -> service.create("가을 신메뉴", "가을 느낌", ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_LIMIT_EXCEEDED));
        verify(templateRepository, never()).save(any());
    }

    @Test
    @DisplayName("LLM 응답이 시간 초과되면 POSTER-005 이고 저장하지 않는다")
    void LLM_시간_초과는_503이다() {
        when(templateRepository.count()).thenReturn(0L);
        TemplateGenerationTimeoutException timeout = new TemplateGenerationTimeoutException("30초 초과");
        when(templateHtmlGenerator.generate("가을 느낌")).thenThrow(timeout);

        assertThatThrownBy(() -> service.create("가을 신메뉴", "가을 느낌", ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.GENERATION_TIMEOUT);
                    assertThat(e.getCause()).isSameAs(timeout);
                });
        verify(templateRepository, never()).save(any());
    }

    @Test
    @DisplayName("슬롯이 빠진 HTML 은 POSTER-001 이고 저장하지 않는다")
    void 슬롯이_빠지면_저장하지_않는다() {
        when(templateRepository.count()).thenReturn(0L);
        when(templateHtmlGenerator.generate("가을 느낌")).thenReturn("<div data-slot=\"eventName\">title</div>");

        assertThatThrownBy(() -> service.create("가을 신메뉴", "가을 느낌", ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.SLOT_CONTRACT_VIOLATION));
        verifyNoInteractions(transactionTemplate);
        verify(templateRepository, never()).save(any());
    }

    @Test
    @DisplayName("팔레트 밖 색상이 있으면 POSTER-002 이고 저장하지 않는다")
    void 팔레트_밖_색상이면_저장하지_않는다() {
        when(templateRepository.count()).thenReturn(0L);
        when(templateHtmlGenerator.generate("가을 느낌")).thenReturn(VALID_HTML.replace("#000000", "#123456"));

        assertThatThrownBy(() -> service.create("가을 신메뉴", "가을 느낌", ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.COLOR_NOT_ALLOWED));
        verifyNoInteractions(transactionTemplate);
        verify(templateRepository, never()).save(any());
    }

    private void setId(Object entity, Long id) throws Exception {
        Field field = BaseTimeEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
