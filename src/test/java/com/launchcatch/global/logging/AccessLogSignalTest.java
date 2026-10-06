package com.launchcatch.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AccessLogSignalTest {

    @Test
    void 표시하지_않은_요청은_예상된_응답이_아니다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();

        // when
        boolean expected = AccessLogSignal.isExpected(request);

        // then
        assertThat(expected).isFalse();
    }

    @Test
    void 표시한_요청은_예상된_응답이다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        AccessLogSignal.markExpected(request);

        // when
        boolean expected = AccessLogSignal.isExpected(request);

        // then
        assertThat(expected).isTrue();
    }

    @Test
    void 표시는_그_요청에만_남고_다른_요청에는_번지지_않는다() {
        // given
        MockHttpServletRequest marked = new MockHttpServletRequest();
        MockHttpServletRequest other = new MockHttpServletRequest();
        AccessLogSignal.markExpected(marked);

        // when
        boolean expected = AccessLogSignal.isExpected(other);

        // then
        assertThat(expected).isFalse();
    }
}
