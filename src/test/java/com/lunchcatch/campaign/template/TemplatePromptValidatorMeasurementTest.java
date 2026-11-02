package com.lunchcatch.campaign.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lunchcatch.campaign.exception.CampaignException;
import com.lunchcatch.campaign.template.PromptMeasureSamples.Sample;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/*
 * 요청 문구 검증기가 표본을 얼마나 막고 통과시키는지 잰다(#1089). 표본의 정의는
 * docs/troubleshooting/campaign/template-prompt-validator-measure-definition.md 가 갖는다.
 *
 * 숨은 문자는 전수 단언한다. 나머지는 결과만 기록하고 단언하지 않는다. 미탐이나 오탐이 나와도 그것이
 * 측정 결과이지 테스트 실패가 아니기 때문이다. 집계는 build/measure 아래 파일로 남고 커밋하지 않는다.
 */
class TemplatePromptValidatorMeasurementTest {

    private static final Path OUTPUT = Path.of("build/measure/prompt-validator-measure.md");
    private static final String INVALID_CHARACTERS_CODE = "POSTER-023";

    private static final TemplatePromptValidator VALIDATOR = new TemplatePromptValidator();
    private static final List<Sample> SAMPLES = PromptMeasureSamples.loadAll();
    private static final PromptMeasureReport REPORT = new PromptMeasureReport();

    static Stream<Sample> hiddenCharSamples() {
        return SAMPLES.stream().filter(s -> PromptMeasureSamples.CATEGORY_HIDDEN_CHARS.equals(s.category()));
    }

    static Stream<Sample> allSamples() {
        return SAMPLES.stream();
    }

    @Test
    @DisplayName("평가셋은 확정한 범주별 건수를 그대로 유지한다")
    void 평가셋의_범주별_건수를_고정한다() {
        assertThat(SAMPLES).hasSize(383);
        assertThat(countOf(PromptMeasureSamples.CATEGORY_HIDDEN_CHARS)).isEqualTo(57);
        assertThat(countOf(PromptMeasureSamples.CATEGORY_INJECTION)).isEqualTo(104);
        assertThat(countOf(PromptMeasureSamples.CATEGORY_PERSONAL_INFO)).isEqualTo(52);
        assertThat(countOf(PromptMeasureSamples.CATEGORY_TOO_SHORT)).isEqualTo(11);
        assertThat(countOf(PromptMeasureSamples.CATEGORY_NORMAL)).isEqualTo(145);
        assertThat(countOf(PromptMeasureSamples.CATEGORY_BORDERLINE)).isEqualTo(14);
    }

    private static long countOf(String category) {
        return SAMPLES.stream().filter(s -> category.equals(s.category())).count();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hiddenCharSamples")
    @DisplayName("숨은 문자 표본은 전부 POSTER-023 으로 차단된다")
    void 숨은_문자_표본은_차단된다(Sample sample) {
        assertThatThrownBy(() -> VALIDATOR.validate(sample.text()))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode().getCode()).isEqualTo(INVALID_CHARACTERS_CODE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allSamples")
    @DisplayName("모든 표본을 검증기에 넣고 결과를 기록한다")
    void 표본을_검증기에_넣어_결과를_기록한다(Sample sample) {
        assertThatCode(() -> REPORT.record(sample, VALIDATOR)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("검증 1건의 처리 시간을 잰다")
    void 처리_시간을_잰다() {
        assertThatCode(() -> REPORT.recordTiming(VALIDATOR, SAMPLES)).doesNotThrowAnyException();
    }

    @AfterAll
    static void 결과를_파일로_남긴다() throws IOException {
        REPORT.writeTo(OUTPUT);
    }
}
