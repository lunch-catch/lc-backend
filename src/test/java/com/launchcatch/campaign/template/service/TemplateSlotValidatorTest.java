package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TemplateSlotValidatorTest {

    private static final String VALID = "<div>"
            + "<span data-slot=\"adLabel\">ad</span>"
            + "<h1 data-slot=\"eventName\">title</h1>"
            + "<p data-slot=\"discount\">discount</p>"
            + "<p data-slot=\"period\">period</p>"
            + "<img data-slot=\"image\" alt=\"menu\">"
            + "</div>";

    private final TemplateSlotValidator validator = new TemplateSlotValidator();

    @Test
    @DisplayName("슬롯 다섯 개가 각각 하나씩 허용된 태그에 있으면 통과한다")
    void 슬롯이_모두_있으면_통과한다() {
        assertThatCode(() -> validator.validate(VALID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("슬롯이 하나라도 빠지면 POSTER-001 이다")
    void 슬롯이_빠지면_실패한다() {
        String missing = VALID.replace("<p data-slot=\"period\">period</p>", "");

        assertViolation(missing);
    }

    @Test
    @DisplayName("같은 슬롯이 두 번 있으면 POSTER-001 이다")
    void 슬롯이_중복되면_실패한다() {
        String duplicated = VALID.replace("</div>", "<p data-slot=\"discount\">again</p></div>");

        assertViolation(duplicated);
    }

    @Test
    @DisplayName("이미지 슬롯이 img 태그가 아니면 POSTER-001 이다")
    void 이미지_슬롯의_태그가_틀리면_실패한다() {
        String wrongTag = VALID.replace("<img data-slot=\"image\" alt=\"menu\">", "<div data-slot=\"image\"></div>");

        assertViolation(wrongTag);
    }

    @Test
    @DisplayName("글 슬롯이 허용되지 않은 태그에 있으면 POSTER-001 이다")
    void 글_슬롯의_태그가_틀리면_실패한다() {
        String wrongTag = VALID.replace("<h1 data-slot=\"eventName\">title</h1>", "<img data-slot=\"eventName\">");

        assertViolation(wrongTag);
    }

    private void assertViolation(String html) {
        assertThatThrownBy(() -> validator.validate(html))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.SLOT_CONTRACT_VIOLATION));
    }
}
