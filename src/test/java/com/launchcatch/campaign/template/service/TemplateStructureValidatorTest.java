package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TemplateStructureValidatorTest {

    private static final String HTML = "<div>"
            + "<span data-slot=\"adLabel\">ad</span>"
            + "<h1 data-slot=\"eventName\">title</h1>"
            + "<p data-slot=\"discount\">discount</p>"
            + "<p data-slot=\"period\">period</p>"
            + "<img data-slot=\"image\" alt=\"menu\">"
            + "</div>";

    private final TemplateStructureValidator validator = new TemplateStructureValidator();

    @Test
    @DisplayName("슬롯별 태그가 같으면 통과한다")
    void 슬롯_태그가_같으면_통과한다() {
        assertThatCode(() -> validator.validate(HTML, HTML)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("내용만 바뀌고 슬롯 태그가 같으면 통과한다")
    void 내용만_바뀌면_통과한다() {
        String changed = HTML
                .replace("<h1 data-slot=\"eventName\">title</h1>", "<h1 data-slot=\"eventName\">다른 제목</h1>")
                .replace("<p data-slot=\"discount\">discount</p>", "<p data-slot=\"discount\">다른 할인</p>");

        assertThatCode(() -> validator.validate(changed, HTML)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("슬롯의 태그가 바뀌면 POSTER-002 이다")
    void 슬롯_태그가_바뀌면_거부한다() {
        String changed = HTML.replace("<h1 data-slot=\"eventName\">title</h1>", "<span data-slot=\"eventName\">title</span>");

        assertThatThrownBy(() -> validator.validate(changed, HTML))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.STRUCTURE_CHANGED));
    }
}
