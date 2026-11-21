package com.lunchcatch.campaign.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lunchcatch.campaign.template.SlowCallMeasureSettings.Mode;
import com.lunchcatch.global.measure.MeasureSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

/*
 * 느린 모델 호출 측정 설정(#1269)이 환경변수를 약속대로 읽는지 본다. 컨텍스트를 띄우지 않아 몇 초 안에 끝난다.
 */
class SlowCallMeasureSettingsTest {

    @Test
    void 환경변수가_없으면_짧은_기본값을_쓴다() {
        SlowCallMeasureSettings settings = SlowCallMeasureSettings.from(name -> null);

        assertThat(settings.delayMillis()).isEqualTo(1_000L);
        assertThat(settings.modes()).containsExactly(Mode.SINGLE, Mode.CORRECTION_RETRY);
        assertThat(settings.rounds()).isEqualTo(2);
    }

    @Test
    void 환경변수로_지연과_조건과_반복_횟수를_바꾼다() {
        SlowCallMeasureSettings settings = SlowCallMeasureSettings.from(Map.of(
                SlowCallMeasureSettings.DELAY_ENV, " 18500 ",
                SlowCallMeasureSettings.MODES_ENV, "CORRECTION_RETRY",
                MeasureSettings.ROUNDS_ENV, "10")::get);

        assertThat(settings.delayMillis()).isEqualTo(18_500L);
        assertThat(settings.modes()).containsExactly(Mode.CORRECTION_RETRY);
        assertThat(settings.rounds()).isEqualTo(10);
    }

    @Test
    void 같은_조건을_두_번_주면_한_번만_돈다() {
        SlowCallMeasureSettings settings = SlowCallMeasureSettings.from(
                Map.of(SlowCallMeasureSettings.MODES_ENV, "SINGLE, SINGLE")::get);

        assertThat(settings.modes()).containsExactly(Mode.SINGLE);
    }

    @Test
    void 지연이_잘못되면_기본값으로_대신하지_않고_예외를_던진다() {
        assertThatThrownBy(() -> SlowCallMeasureSettings.from(Map.of(SlowCallMeasureSettings.DELAY_ENV, "abc")::get))
                .isInstanceOf(IllegalArgumentException.class);
        // 요청 8개가 겹치지 않을 만큼 짧은 지연은 거부한다.
        assertThatThrownBy(() -> SlowCallMeasureSettings.from(Map.of(SlowCallMeasureSettings.DELAY_ENV, "499")::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SlowCallMeasureSettings.from(Map.of(SlowCallMeasureSettings.DELAY_ENV, "500")::get).delayMillis())
                .isEqualTo(500L);
        // 실제 SDK 가 끊는 30초보다 긴 지연은 거부한다.
        assertThat(SlowCallMeasureSettings.from(Map.of(SlowCallMeasureSettings.DELAY_ENV, "30000")::get).delayMillis())
                .isEqualTo(30_000L);
        assertThatThrownBy(() -> SlowCallMeasureSettings.from(Map.of(SlowCallMeasureSettings.DELAY_ENV, "30001")::get))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 조건이_잘못되면_예외를_던진다() {
        assertThatThrownBy(() -> SlowCallMeasureSettings.from(Map.of(SlowCallMeasureSettings.MODES_ENV, "single")::get))
                .isInstanceOf(IllegalArgumentException.class);
        // 끝이나 가운데의 빈 항목은 버리지 않고 거부한다.
        assertThatThrownBy(() -> SlowCallMeasureSettings.from(Map.of(SlowCallMeasureSettings.MODES_ENV, "SINGLE,")::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlowCallMeasureSettings.from(
                Map.of(SlowCallMeasureSettings.MODES_ENV, "SINGLE,,CORRECTION_RETRY")::get))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 반복_횟수가_잘못되면_예외를_던진다() {
        assertThatThrownBy(() -> SlowCallMeasureSettings.from(Map.of(MeasureSettings.ROUNDS_ENV, "0")::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlowCallMeasureSettings.from(Map.of(MeasureSettings.ROUNDS_ENV, "abc")::get))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
