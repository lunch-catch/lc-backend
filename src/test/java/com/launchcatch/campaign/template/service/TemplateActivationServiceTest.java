package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.launchcatch.admin.contract.AdminNameQuery;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.dto.TemplateActiveUpdateResponse;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import com.launchcatch.global.entity.BaseTimeEntity;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TemplateActivationServiceTest {

    private static final Long TEMPLATE_ID = 1L;
    private static final Long ADMIN_ID = 7L;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-12T01:00:00Z"), ZoneOffset.UTC);

    @Mock
    private TemplateRepository templateRepository;
    @Mock
    private AdminNameQuery adminNameQuery;

    private TemplateActivationService service;

    @BeforeEach
    void setUp() {
        service = new TemplateActivationService(templateRepository, adminNameQuery, FIXED_CLOCK);
    }

    @Test
    @DisplayName("게시된 템플릿의 활성 여부를 바꾸면 변경자와 변경 시각이 함께 갱신된다")
    void 활성화하면_변경자와_시각이_함께_갱신된다() throws Exception {
        Template template = publishedTemplate();
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(adminNameQuery.findNamesByIds(List.of(ADMIN_ID))).thenReturn(Map.of(ADMIN_ID, "홍길동"));

        TemplateActiveUpdateResponse response = service.changeActive(TEMPLATE_ID, true, ADMIN_ID);

        assertThat(response.templateId()).isEqualTo(TEMPLATE_ID);
        assertThat(response.active()).isTrue();
        assertThat(response.activatedBy()).isEqualTo(ADMIN_ID);
        assertThat(response.activatedByName()).isEqualTo("홍길동");
        assertThat(response.activatedAt()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));
    }

    @Test
    @DisplayName("템플릿이 없으면 POSTER-005 로 거부한다")
    void 템플릿이_없으면_거부한다() {
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeActive(TEMPLATE_ID, true, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_NOT_FOUND));
    }

    @Test
    @DisplayName("게시되지 않은 템플릿은 POSTER-009 로 거부한다")
    void 게시되지_않은_템플릿은_거부한다() throws Exception {
        Template template = Template.createDraft("가을 신메뉴");
        setId(template, TEMPLATE_ID);
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> service.changeActive(TEMPLATE_ID, true, ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_NOT_PUBLISHED));
    }

    private Template publishedTemplate() throws Exception {
        Template template = Template.createDraft("가을 신메뉴");
        setId(template, TEMPLATE_ID);
        template.publish("<div>1</div>", LocalDateTime.now(FIXED_CLOCK), 1);
        return template;
    }

    private void setId(Object entity, Long id) throws Exception {
        Field field = BaseTimeEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
