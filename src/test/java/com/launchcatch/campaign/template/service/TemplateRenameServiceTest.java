package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.dto.TemplateRenameResponse;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.repository.TemplateRepository;
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
class TemplateRenameServiceTest {

    private static final Long TEMPLATE_ID = 1L;
    private static final Long ADMIN_ID = 7L;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-24T01:00:00Z"), ZoneOffset.UTC);

    @Mock
    private TemplateRepository templateRepository;

    private TemplateRenameService service;

    @BeforeEach
    void setUp() {
        service = new TemplateRenameService(templateRepository, FIXED_CLOCK);
    }

    @Test
    @DisplayName("이름을 바꾸면 마지막 수정자와 수정 시각을 함께 갱신한다")
    void 이름을_바꾼다() {
        Template template = Template.createDraft("가을 신메뉴");
        when(templateRepository.findByIdForUpdate(TEMPLATE_ID)).thenReturn(Optional.of(template));

        TemplateRenameResponse response = service.rename(TEMPLATE_ID, "겨울 신메뉴", ADMIN_ID);

        assertThat(response.name()).isEqualTo("겨울 신메뉴");
        assertThat(response.lastModifiedBy()).isEqualTo(ADMIN_ID);
        assertThat(response.lastModifiedAt()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));
        assertThat(template.getName()).isEqualTo("겨울 신메뉴");
    }

    @Test
    @DisplayName("게시된 템플릿도 이름을 바꿀 수 있고 활성 상태와 게시본은 그대로다")
    void 게시된_템플릿도_이름을_바꾼다() {
        Template template = Template.createDraft("가을 신메뉴");
        template.addDraftVersion(1L, LocalDateTime.now(FIXED_CLOCK), "요청", "req-1", "<div>1</div>", 1);
        template.publish("<div>1</div>", LocalDateTime.now(FIXED_CLOCK), 1);
        template.changeActive(true, 1L, LocalDateTime.now(FIXED_CLOCK));
        when(templateRepository.findByIdForUpdate(TEMPLATE_ID)).thenReturn(Optional.of(template));

        service.rename(TEMPLATE_ID, "겨울 신메뉴", ADMIN_ID);

        assertThat(template.getName()).isEqualTo("겨울 신메뉴");
        assertThat(template.isActive()).isTrue();
        assertThat(template.getHtmlContent()).isEqualTo("<div>1</div>");
    }

    @Test
    @DisplayName("템플릿이 없으면 POSTER-005 로 거부한다")
    void 템플릿이_없으면_거부한다() {
        when(templateRepository.findByIdForUpdate(TEMPLATE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rename(TEMPLATE_ID, "겨울 신메뉴", ADMIN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_NOT_FOUND));
    }
}
