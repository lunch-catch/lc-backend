package com.launchcatch.campaign.template.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TemplateTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 8, 10, 0);

    @Test
    @DisplayName("템플릿은 임시저장 상태이고 비활성이며 확정 HTML이 비어 있는 채로 만들어진다")
    void 템플릿은_임시저장으로_시작한다() {
        Template template = Template.createDraft("가을 신메뉴");

        assertThat(template.getName()).isEqualTo("가을 신메뉴");
        assertThat(template.getStatus()).isEqualTo(TemplateStatus.DRAFT);
        assertThat(template.isActive()).isFalse();
        assertThat(template.getHtmlContent()).isNull();
        assertThat(template.getPublishedAt()).isNull();
        assertThat(template.getLastModifiedBy()).isNull();
        assertThat(template.getLastModifiedAt()).isNull();
    }

    @Test
    @DisplayName("이름이 비었거나 100자를 넘으면 만들 수 없다")
    void 이름이_잘못되면_만들_수_없다() {
        assertThatThrownBy(() -> Template.createDraft(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Template.createDraft(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Template.createDraft("가".repeat(101))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Template.createDraft("가".repeat(100)).getName()).hasSize(100);
    }

    @Test
    @DisplayName("버전을 더하면 번호가 1부터 이어서 붙고 최종 수정자와 시각이 갱신된다")
    void 버전_번호는_이어서_붙는다() {
        Template template = Template.createDraft("가을 신메뉴");

        TemplateVersion first = template.addDraftVersion(1L, NOW, "요청 하나", "req-1", "<div></div>");
        TemplateVersion second = template.addDraftVersion(2L, NOW.plusMinutes(1), "요청 둘", "req-2", "<p></p>");

        assertThat(first.getVersionNumber()).isEqualTo(1);
        assertThat(first.getRequestPrompt()).isEqualTo("요청 하나");
        assertThat(first.getRequestId()).isEqualTo("req-1");
        assertThat(first.getHtmlContent()).isEqualTo("<div></div>");
        assertThat(first.getTemplate()).isSameAs(template);
        assertThat(second.getVersionNumber()).isEqualTo(2);
        assertThat(template.getLastModifiedBy()).isEqualTo(2L);
        assertThat(template.getLastModifiedAt()).isEqualTo(NOW.plusMinutes(1));
    }

    @Test
    @DisplayName("요청 문장, 요청 식별자, HTML 중 하나라도 비어 있으면 버전을 만들 수 없다")
    void 요청_문장이나_HTML이_비면_버전을_만들_수_없다() {
        Template template = Template.createDraft("가을 신메뉴");

        assertThatThrownBy(() -> template.addDraftVersion(1L, NOW, " ", "req-1", "<div></div>"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.addDraftVersion(1L, NOW, "요청", " ", "<div></div>"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.addDraftVersion(1L, NOW, "요청", "req-1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("최신 버전은 버전 번호가 가장 큰 것이다")
    void 최신_버전은_번호가_가장_크다() {
        Template template = Template.createDraft("가을 신메뉴");
        template.addDraftVersion(1L, NOW, "요청 하나", "req-1", "<div></div>");
        TemplateVersion second = template.addDraftVersion(1L, NOW, "요청 둘", "req-2", "<p></p>");

        assertThat(template.latestVersion()).isSameAs(second);
    }

    @Test
    @DisplayName("버전이 하나도 없으면 최신 버전을 가져올 수 없다")
    void 버전이_없으면_최신_버전을_가져올_수_없다() {
        Template template = Template.createDraft("가을 신메뉴");

        assertThatThrownBy(template::latestVersion).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("버전 번호가 1보다 작으면 만들 수 없다")
    void 버전_번호는_양수여야_한다() {
        Template template = Template.createDraft("가을 신메뉴");

        assertThatThrownBy(() -> TemplateVersion.create(template, 0, "요청", "req-1", "<div></div>"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
