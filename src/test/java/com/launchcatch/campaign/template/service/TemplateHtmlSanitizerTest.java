package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.campaign.template.dto.TemplateSanitizeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TemplateHtmlSanitizerTest {

    private final TemplateHtmlSanitizer sanitizer = new TemplateHtmlSanitizer();

    @Test
    @DisplayName("허용된 태그와 속성만 있으면 그대로 두고 제거 목록은 비어 있다")
    void 허용된_것만_있으면_그대로_둔다() {
        String html = "<div class=\"poster\"><h1 data-slot=\"eventName\">title</h1>"
                + "<img data-slot=\"image\" alt=\"menu\"></div>";

        TemplateSanitizeResult result = sanitizer.sanitize(html);

        assertThat(result.html()).isEqualTo(html);
        assertThat(result.removedElements()).isEmpty();
    }

    @Test
    @DisplayName("스크립트는 안의 내용까지 제거하고 제거 목록에 남긴다")
    void 스크립트를_제거한다() {
        TemplateSanitizeResult result = sanitizer.sanitize(
                "<div data-slot=\"eventName\">title</div><script>alert(1)</script>");

        assertThat(result.html()).isEqualTo("<div data-slot=\"eventName\">title</div>");
        assertThat(result.removedElements()).containsExactly("script");
    }

    @Test
    @DisplayName("같은 태그를 여러 번 제거해도 제거 목록에는 한 번만 남는다")
    void 제거_목록은_중복이_없다() {
        TemplateSanitizeResult result = sanitizer.sanitize("<script>a</script><script>b</script>");

        assertThat(result.removedElements()).containsExactly("script");
    }

    @Test
    @DisplayName("이벤트 속성은 제거하고 태그와 속성 이름을 제거 목록에 남긴다")
    void 이벤트_속성을_제거한다() {
        TemplateSanitizeResult result = sanitizer.sanitize("<div data-slot=\"eventName\" onclick=\"x()\">title</div>");

        assertThat(result.html()).isEqualTo("<div data-slot=\"eventName\">title</div>");
        assertThat(result.removedElements()).containsExactly("div[onclick]");
    }

    @Test
    @DisplayName("이미지의 외부 주소는 제거한다")
    void 이미지_주소를_제거한다() {
        TemplateSanitizeResult result = sanitizer.sanitize(
                "<img data-slot=\"image\" src=\"https://example.com/a.png\" alt=\"menu\">");

        assertThat(result.html()).isEqualTo("<img data-slot=\"image\" alt=\"menu\">");
        assertThat(result.removedElements()).containsExactly("img[src]");
    }

    @Test
    @DisplayName("허용되지 않은 태그는 태그만 벗기고 안의 내용은 남긴다")
    void 허용되지_않은_태그는_벗긴다() {
        TemplateSanitizeResult result = sanitizer.sanitize(
                "<section><article><div data-slot=\"eventName\">title</div></article></section>");

        assertThat(result.html()).isEqualTo("<div data-slot=\"eventName\">title</div>");
        assertThat(result.removedElements()).containsExactly("section", "article");
    }

    @Test
    @DisplayName("style 에 외부 리소스가 있으면 style 속성을 제거한다")
    void 외부_리소스가_있는_style을_제거한다() {
        TemplateSanitizeResult result = sanitizer.sanitize(
                "<div style=\"background:url(http://example.com/a.png)\">title</div>");

        assertThat(result.html()).isEqualTo("<div>title</div>");
        assertThat(result.removedElements()).containsExactly("div[style]");
    }

    @Test
    @DisplayName("안전한 style 은 그대로 둔다")
    void 안전한_style은_남긴다() {
        TemplateSanitizeResult result = sanitizer.sanitize("<div style=\"color:#000000\">title</div>");

        assertThat(result.html()).isEqualTo("<div style=\"color:#000000\">title</div>");
        assertThat(result.removedElements()).isEmpty();
    }

    @Test
    @DisplayName("역슬래시로 금칙어를 피해도 style 전체를 제거한다")
    void 역슬래시로_우회해도_제거한다() {
        TemplateSanitizeResult result = sanitizer.sanitize(
                "<div style=\"color:\\75rl(javascript:alert(1))\">title</div>");

        assertThat(result.html()).isEqualTo("<div>title</div>");
        assertThat(result.removedElements()).containsExactly("div[style]");
    }

    @Test
    @DisplayName("한글 내용은 그대로 보존한다")
    void 한글_내용을_보존한다() {
        TemplateSanitizeResult result = sanitizer.sanitize("<h1 data-slot=\"eventName\">가을 신메뉴</h1>");

        assertThat(result.html()).contains("가을 신메뉴");
    }
}
