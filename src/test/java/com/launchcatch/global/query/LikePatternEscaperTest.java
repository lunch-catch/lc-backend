package com.launchcatch.global.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class LikePatternEscaperTest {

    @Test
    void 일반_문자열은_변경하지_않는다() {
        // given
        String input = "런치캐치";

        // when
        String escaped = LikePatternEscaper.escapeLiteral(input);

        // then
        assertThat(escaped).isEqualTo("런치캐치");
    }

    @Test
    void 퍼센트는_리터럴로_이스케이프한다() {
        // given
        String input = "100%";

        // when
        String escaped = LikePatternEscaper.escapeLiteral(input);

        // then
        assertThat(escaped).isEqualTo("100!%");
    }

    @Test
    void 밑줄은_리터럴로_이스케이프한다() {
        // given
        String input = "a_b";

        // when
        String escaped = LikePatternEscaper.escapeLiteral(input);

        // then
        assertThat(escaped).isEqualTo("a!_b");
    }

    @Test
    void 이스케이프_문자는_먼저_이스케이프한다() {
        // given
        String input = "a!b";

        // when
        String escaped = LikePatternEscaper.escapeLiteral(input);

        // then
        assertThat(escaped).isEqualTo("a!!b");
    }

    @Test
    void LIKE_예약문자가_섞여도_모두_리터럴로_이스케이프한다() {
        // given
        String input = "!_%";

        // when
        String escaped = LikePatternEscaper.escapeLiteral(input);

        // then
        assertThat(escaped).isEqualTo("!!!_!%");
    }

    @Test
    void null은_허용하지_않는다() {
        // when, then
        assertThatNullPointerException()
                .isThrownBy(() -> LikePatternEscaper.escapeLiteral(null))
                .withMessage("input must not be null");
    }
}
