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
    @DisplayName("버전을 더하면 넘긴 번호로 저장되고 최종 수정자와 시각이 갱신된다")
    void 버전을_더하면_넘긴_번호로_저장된다() {
        Template template = Template.createDraft("가을 신메뉴");

        TemplateVersion first = template.addDraftVersion(1L, NOW, "요청 하나", "req-1", "<div></div>", 1);
        TemplateVersion second = template.addDraftVersion(2L, NOW.plusMinutes(1), "요청 둘", "req-2", "<p></p>", 2);

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

        assertThatThrownBy(() -> template.addDraftVersion(1L, NOW, " ", "req-1", "<div></div>", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.addDraftVersion(1L, NOW, "요청", " ", "<div></div>", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.addDraftVersion(1L, NOW, "요청", "req-1", null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("최신 버전은 버전 번호가 가장 큰 것이다")
    void 최신_버전은_번호가_가장_크다() {
        Template template = Template.createDraft("가을 신메뉴");
        template.addDraftVersion(1L, NOW, "요청 하나", "req-1", "<div></div>", 1);
        TemplateVersion second = template.addDraftVersion(1L, NOW, "요청 둘", "req-2", "<p></p>", 2);

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

    @Test
    @DisplayName("게시하면 HTML과 상태, 게시 시각, 게시 버전 번호가 함께 바뀐다")
    void 게시하면_상태와_시각이_함께_바뀐다() {
        Template template = Template.createDraft("가을 신메뉴");

        template.publish("<div>확정</div>", NOW, 3);

        assertThat(template.getHtmlContent()).isEqualTo("<div>확정</div>");
        assertThat(template.getStatus()).isEqualTo(TemplateStatus.PUBLISHED);
        assertThat(template.getPublishedAt()).isEqualTo(NOW);
        assertThat(template.getPublishedVersionNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("활성화 여부를 바꾸면 변경자와 변경 시각도 같이 바뀐다")
    void 활성화하면_변경자와_시각이_함께_바뀐다() {
        Template template = Template.createDraft("가을 신메뉴");
        template.publish("<div>확정</div>", NOW, 1);

        template.changeActive(true, 7L, NOW);

        assertThat(template.isActive()).isTrue();
        assertThat(template.getActivatedBy()).isEqualTo(7L);
        assertThat(template.getActivatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("DRAFT가 아니면 다시 게시할 수 없다")
    void draft가_아니면_게시할_수_없다() {
        Template template = Template.createDraft("가을 신메뉴");
        template.publish("<div>확정</div>", NOW, 1);

        assertThatThrownBy(() -> template.publish("<div>또확정</div>", NOW, 2))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("PUBLISHED가 아니면 활성화 여부를 바꿀 수 없다")
    void published가_아니면_활성화할_수_없다() {
        Template template = Template.createDraft("가을 신메뉴");

        assertThatThrownBy(() -> template.changeActive(true, 7L, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("재게시하면 HTML과 게시 시각, 게시 버전 번호, 수정자가 바뀌고 활성 상태는 그대로다(#880)")
    void 재게시하면_게시본이_바뀌고_active는_유지된다() {
        Template template = Template.createDraft("가을 신메뉴");
        template.publish("<div>확정</div>", NOW, 1);
        template.changeActive(true, 7L, NOW);
        LocalDateTime later = NOW.plusDays(1);

        template.republish("<div>새 확정</div>", later, 4, 9L);

        assertThat(template.getHtmlContent()).isEqualTo("<div>새 확정</div>");
        assertThat(template.getStatus()).isEqualTo(TemplateStatus.PUBLISHED);
        assertThat(template.getPublishedAt()).isEqualTo(later);
        assertThat(template.getPublishedVersionNumber()).isEqualTo(4);
        assertThat(template.getLastModifiedBy()).isEqualTo(9L);
        assertThat(template.getLastModifiedAt()).isEqualTo(later);
        assertThat(template.isActive()).isTrue();
        assertThat(template.getActivatedBy()).isEqualTo(7L);
    }

    @Test
    @DisplayName("PUBLISHED가 아니면 재게시할 수 없다(#880)")
    void published가_아니면_재게시할_수_없다() {
        Template template = Template.createDraft("가을 신메뉴");

        assertThatThrownBy(() -> template.republish("<div>x</div>", NOW, 2, 9L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("이름을 바꾸면 수정자와 수정 시각이 바뀌고 상태와 무관하다(#880)")
    void 이름을_바꾸면_수정자와_시각이_바뀐다() {
        Template template = Template.createDraft("가을 신메뉴");

        template.rename("겨울 신메뉴", 9L, NOW);

        assertThat(template.getName()).isEqualTo("겨울 신메뉴");
        assertThat(template.getLastModifiedBy()).isEqualTo(9L);
        assertThat(template.getLastModifiedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("이름이 비었거나 100자를 넘으면 바꿀 수 없다(#880)")
    void 잘못된_이름으로는_바꿀_수_없다() {
        Template template = Template.createDraft("가을 신메뉴");

        assertThatThrownBy(() -> template.rename(" ", 9L, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.rename("가".repeat(101), 9L, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(template.getName()).isEqualTo("가을 신메뉴");
    }
}
