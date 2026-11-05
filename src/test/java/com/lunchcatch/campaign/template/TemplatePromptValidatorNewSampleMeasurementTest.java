package com.lunchcatch.campaign.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
 * 요청 문구 검증기를 수정하기 전에 확정한 새 표본으로 잰다(#1098). 새 표본은 패턴과 규칙을 고치는 데 쓰지 않았다.
 * 기존 표본의 측정은 TemplatePromptValidatorMeasurementTest 가 갖고, 이 테스트는 같은 방식을 새 표본에 적용한다.
 * 결과만 기록하고 미탐과 오탐은 단언하지 않는다. 집계는 build/measure 아래 파일로 남고 커밋하지 않는다.
 */
class TemplatePromptValidatorNewSampleMeasurementTest {

    private static final Path OUTPUT = Path.of("build/measure/prompt-validator-measure-new.md");

    private static final TemplatePromptValidator VALIDATOR = new TemplatePromptValidator();
    private static final List<Sample> SAMPLES = PromptMeasureSamples.loadNew();
    private static final PromptMeasureReport REPORT = new PromptMeasureReport();

    static Stream<Sample> allSamples() {
        return SAMPLES.stream();
    }

    @Test
    @DisplayName("새 표본은 수정 전에 확정한 범주별 건수를 그대로 유지한다")
    void 새_표본의_범주별_건수를_고정한다() {
        assertThat(SAMPLES).hasSize(157);
        assertThat(countOf(PromptMeasureSamples.CATEGORY_INJECTION)).isEqualTo(52);
        assertThat(countOf(PromptMeasureSamples.CATEGORY_PERSONAL_INFO)).isEqualTo(26);
        assertThat(countOf(PromptMeasureSamples.CATEGORY_NORMAL)).isEqualTo(72);
        assertThat(countOf(PromptMeasureSamples.CATEGORY_BORDERLINE)).isEqualTo(7);
    }

    private static long countOf(String category) {
        return SAMPLES.stream().filter(s -> category.equals(s.category())).count();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allSamples")
    @DisplayName("새 표본을 검증기에 넣고 결과를 기록한다")
    void 새_표본을_검증기에_넣어_결과를_기록한다(Sample sample) {
        assertThatCode(() -> REPORT.record(sample, VALIDATOR)).doesNotThrowAnyException();
    }

    @AfterAll
    static void 결과를_파일로_남긴다() throws IOException {
        REPORT.writeTo(OUTPUT);
    }
}
