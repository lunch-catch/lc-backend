package com.launchcatch.campaign.template.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.launchcatch.campaign.template.dto.TemplateSanitizeResult;
import com.launchcatch.campaign.template.service.TemplateHtmlSanitizer;
import com.launchcatch.campaign.template.service.TemplateSlotValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FakeTemplateHtmlGeneratorTest {

    @Test
    @DisplayName("가짜 구현이 만든 HTML 은 정화와 슬롯 검증을 모두 통과한다")
    void 가짜_HTML은_검증을_통과한다() {
        String html = new FakeTemplateHtmlGenerator().generate("아무 문장");

        TemplateSanitizeResult sanitized = new TemplateHtmlSanitizer().sanitize(html);

        assertThat(sanitized.removedElements()).isEmpty();
        assertThatCode(() -> new TemplateSlotValidator().validate(sanitized.html())).doesNotThrowAnyException();
    }
}
