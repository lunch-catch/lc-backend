package com.lunchcatch.analytics.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InvalidRatioTest {

    @Test
    void 비율은_무효를_유효로_나눈다() {
        assertThat(InvalidRatio.of(4_000, 1_100)).isEqualTo(0.275);
    }

    @Test
    void 비율은_1을_넘을_수_있다() {
        assertThat(InvalidRatio.of(100, 150)).isEqualTo(1.5);
    }

    @Test
    void 비율은_소수점_여섯_자리로_반올림한다() {
        assertThat(InvalidRatio.of(3, 1)).isEqualTo(0.333333);
    }

    @Test
    void 유효_노출이_0이면_비율은_null이다() {
        assertThat(InvalidRatio.of(0, 120)).isNull();
    }

    @Test
    void 유효_100_무효_21이면_경고한다() {
        assertThat(InvalidRatio.warning(100, 21, 1)).isTrue();
    }

    @Test
    void 비율이_정확히_20퍼센트면_경고하지_않는다() {
        assertThat(InvalidRatio.warning(100, 20, 1)).isFalse();
    }

    @Test
    void 무효가_최소_건수_미만이면_비율이_높아도_경고하지_않는다() {
        assertThat(InvalidRatio.warning(1, 1, 100)).isFalse();
        assertThat(InvalidRatio.warning(100, 99, 100)).isFalse();
    }

    @Test
    void 무효가_최소_건수_이상이고_비율이_20퍼센트를_넘으면_경고한다() {
        assertThat(InvalidRatio.warning(400, 100, 100)).isTrue();
    }

    @Test
    void 유효가_0이어도_무효가_최소_건수_이상이면_경고한다() {
        assertThat(InvalidRatio.warning(0, 100, 100)).isTrue();
    }

    @Test
    void 유효가_0이고_무효가_최소_건수_미만이면_경고하지_않는다() {
        assertThat(InvalidRatio.warning(0, 99, 100)).isFalse();
    }
}
