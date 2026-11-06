package com.lunchcatch.global.measure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/*
 * 규모 측정 설정(#1091)이 환경변수를 약속대로 읽는지 본다. 컨텍스트를 띄우지 않아 몇 초 안에 끝난다.
 */
class ScaleMeasureSettingsTest {

    @Test
    void 규모만_주면_반복_횟수는_기본값을_쓴다() {
        ScaleMeasureSettings settings = ScaleMeasureSettings.from(
                Map.of(ScaleMeasureSettings.SCALE_ENV, "1000, 10000")::get);

        assertThat(settings.scales()).containsExactly(1000, 10000);
        assertThat(settings.rounds()).isEqualTo(3);
    }

    @Test
    void 환경변수로_반복_횟수를_바꾼다() {
        ScaleMeasureSettings settings = ScaleMeasureSettings.from(Map.of(
                ScaleMeasureSettings.SCALE_ENV, "1000",
                MeasureSettings.ROUNDS_ENV, " 5 ")::get);

        assertThat(settings.rounds()).isEqualTo(5);
    }

    @Test
    void 규모가_없거나_잘못되면_기본값으로_대신하지_않고_예외를_던진다() {
        assertThatThrownBy(() -> ScaleMeasureSettings.from(name -> null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScaleMeasureSettings.from(Map.of(ScaleMeasureSettings.SCALE_ENV, " ")::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScaleMeasureSettings.from(Map.of(ScaleMeasureSettings.SCALE_ENV, "1000,x")::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScaleMeasureSettings.from(Map.of(ScaleMeasureSettings.SCALE_ENV, "0")::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScaleMeasureSettings.from(Map.of(ScaleMeasureSettings.SCALE_ENV, "100001")::get))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 반복_횟수가_잘못되면_예외를_던진다() {
        assertThatThrownBy(() -> ScaleMeasureSettings.from(Map.of(
                ScaleMeasureSettings.SCALE_ENV, "1000",
                MeasureSettings.ROUNDS_ENV, "0")::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScaleMeasureSettings.from(Map.of(
                ScaleMeasureSettings.SCALE_ENV, "1000",
                MeasureSettings.ROUNDS_ENV, "abc")::get))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
