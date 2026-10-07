package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TemplatePaletteValidatorTest {

    private final TemplatePaletteValidator validator = new TemplatePaletteValidator();

    @Test
    @DisplayName("style 이 없으면 통과한다")
    void style이_없으면_통과한다() {
        assertThatCode(() -> validator.validate("<div>title</div>")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("팔레트 안의 색상이면 대소문자와 상관없이 통과한다")
    void 팔레트_안의_색상은_통과한다() {
        assertThatCode(() -> validator.validate(
                "<div style=\"color:#ff6b35;background-color:#FFFFFF\">title</div>")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("세 자리 표기는 여섯 자리로 풀어서 비교한다")
    void 세_자리_표기를_풀어서_비교한다() {
        assertThatCode(() -> validator.validate("<div style=\"color:#000\">title</div>")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("팔레트에 없는 색상이면 POSTER-002 이다")
    void 팔레트_밖의_색상은_실패한다() {
        assertViolation("<div style=\"color:#123456\">title</div>");
    }

    @Test
    @DisplayName("함수 표기 색상은 POSTER-002 이다")
    void 함수_표기_색상은_실패한다() {
        assertViolation("<div style=\"color:rgb(0,0,0)\">title</div>");
    }

    @Test
    @DisplayName("색상 속성에 이름 색상을 쓰면 POSTER-002 이다")
    void 이름_색상은_실패한다() {
        assertViolation("<div style=\"background-color:red\">title</div>");
    }

    @Test
    @DisplayName("단축 속성 안의 팔레트 밖 색상도 POSTER-002 이다")
    void 단축_속성_안의_색상도_실패한다() {
        assertViolation("<div style=\"border:1px solid #123456\">title</div>");
    }

    @Test
    @DisplayName("값이 없는 선언은 무시한다")
    void 값이_없는_선언은_무시한다() {
        assertThatCode(() -> validator.validate("<div style=\"display\">title</div>")).doesNotThrowAnyException();
    }

    private void assertViolation(String html) {
        assertThatThrownBy(() -> validator.validate(html))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.COLOR_NOT_ALLOWED));
    }
}
