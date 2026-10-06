package com.launchcatch.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MdcLoggingFilterTest {

    private static final String GENERATED_TRACE_ID = "[0-9a-f]{16}";

    private final MdcLoggingFilter filter = new MdcLoggingFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/v1/members/me");
    }

    // 필터 안쪽에서 보이는 MDC 를 복사해 돌려준다. 요청이 끝나면 MDC 가 비워지기 때문이다.
    private Map<String, String> mdcSeenByChain(MockHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> seen.set(MDC.getCopyOfContextMap()));
        return seen.get();
    }

    @Test
    void 안전한_X_Trace_Id가_들어오면_그대로_이어받는다() throws Exception {
        // given
        MockHttpServletRequest request = request();
        request.addHeader("X-Trace-Id", "abc-123_DEF.456");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        Map<String, String> mdc = mdcSeenByChain(request, response);

        // then
        assertThat(mdc.get("traceId")).isEqualTo("abc-123_DEF.456");
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("abc-123_DEF.456");
    }

    @Test
    void X_Trace_Id가_없으면_열여섯_자리_16진수를_새로_만든다() throws Exception {
        // given
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        Map<String, String> mdc = mdcSeenByChain(request(), response);

        // then
        assertThat(mdc.get("traceId")).matches(GENERATED_TRACE_ID);
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo(mdc.get("traceId"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "line\nbreak", "tag<script>", "한글"})
    void 허용하지_않는_문자가_든_X_Trace_Id는_버리고_새로_만든다(String unsafe) throws Exception {
        // given
        MockHttpServletRequest request = request();
        request.addHeader("X-Trace-Id", unsafe);

        // when
        Map<String, String> mdc = mdcSeenByChain(request, new MockHttpServletResponse());

        // then
        assertThat(mdc.get("traceId")).matches(GENERATED_TRACE_ID);
    }

    @Test
    void 예순다섯_자_X_Trace_Id는_버리고_새로_만든다() throws Exception {
        // given
        MockHttpServletRequest request = request();
        request.addHeader("X-Trace-Id", "a".repeat(65));

        // when
        Map<String, String> mdc = mdcSeenByChain(request, new MockHttpServletResponse());

        // then
        assertThat(mdc.get("traceId")).matches(GENERATED_TRACE_ID);
    }

    @Test
    void 예순네_자_X_Trace_Id는_그대로_이어받는다() throws Exception {
        // given
        String boundary = "a".repeat(64);
        MockHttpServletRequest request = request();
        request.addHeader("X-Trace-Id", boundary);

        // when
        Map<String, String> mdc = mdcSeenByChain(request, new MockHttpServletResponse());

        // then
        assertThat(mdc.get("traceId")).isEqualTo(boundary);
    }

    @Test
    void 메서드와_경로를_MDC에_싣는다() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/auth/tokens");

        // when
        Map<String, String> mdc = mdcSeenByChain(request, new MockHttpServletResponse());

        // then
        assertThat(mdc).containsEntry("method", "POST").containsEntry("uri", "/v1/auth/tokens");
    }

    @Test
    void X_Forwarded_For의_첫_주소를_클라이언트_IP로_쓴다() throws Exception {
        // given
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");

        // when
        Map<String, String> mdc = mdcSeenByChain(request, new MockHttpServletResponse());

        // then
        assertThat(mdc.get("clientIp")).isEqualTo("203.0.113.5");
    }

    @Test
    void IPv6_주소도_클라이언트_IP로_받아들인다() throws Exception {
        // given
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "2001:db8::1");

        // when
        Map<String, String> mdc = mdcSeenByChain(request, new MockHttpServletResponse());

        // then
        assertThat(mdc.get("clientIp")).isEqualTo("2001:db8::1");
    }

    @Test
    void 형식이_틀린_X_Forwarded_For는_버리고_접속_주소를_쓴다() throws Exception {
        // given
        MockHttpServletRequest request = request();
        request.setRemoteAddr("10.1.2.3");
        request.addHeader("X-Forwarded-For", "not an ip");

        // when
        Map<String, String> mdc = mdcSeenByChain(request, new MockHttpServletResponse());

        // then
        assertThat(mdc.get("clientIp")).isEqualTo("10.1.2.3");
    }

    @Test
    void X_Forwarded_For가_없으면_접속_주소를_쓴다() throws Exception {
        // given
        MockHttpServletRequest request = request();
        request.setRemoteAddr("10.1.2.3");

        // when
        Map<String, String> mdc = mdcSeenByChain(request, new MockHttpServletResponse());

        // then
        assertThat(mdc.get("clientIp")).isEqualTo("10.1.2.3");
    }

    @Test
    void 요청이_끝나면_MDC를_비운다() throws Exception {
        // when
        mdcSeenByChain(request(), new MockHttpServletResponse());

        // then
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void 체인에서_예외가_나도_MDC를_비운다() {
        // when
        assertThatThrownBy(() -> filter.doFilter(request(), new MockHttpServletResponse(), (req, res) -> {
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class);

        // then
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
