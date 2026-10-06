package com.launchcatch.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HttpBodyLoggingFilterTest {

    private static final String SECRET = "secret-value";
    private static final String MASKED = "***REDACTED***";

    private final HttpBodyLoggingFilter filter = new HttpBodyLoggingFilter();
    private LogCapture logs;

    @BeforeEach
    void attachLogs() {
        logs = LogCapture.attach(HttpBodyLoggingFilter.class);
    }

    @AfterEach
    void detachLogs() {
        logs.close();
    }

    /*
     * 가려야 하는 키 전체다. 운영 코드의 정규식은 이 목록을 문자열로 들고 있어서
     * 한 줄이 빠지거나 오타가 나도 컴파일은 통과한다.
     */
    static Stream<String> sensitiveKeys() {
        return Stream.of(
                "password", "accessToken", "refreshToken", "token", "qrToken", "secret",
                "authorization", "idToken", "clientSecret",
                "phone", "address", "roadAddress", "locationNickname", "name", "sub",
                "businessRegistrationNumber", "businessNumber",
                "lat", "lng", "latitude", "longitude",
                "currentPassword", "newPassword", "representativeName", "fid",
                "providerUserId", "kakaoUserId", "user_id", "paymentKey",
                "authorizationCode", "state", "nonce");
    }

    private MockHttpServletRequest post(String contentType, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/test");
        request.setContentType(contentType);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private MockHttpServletRequest jsonPost(String body) {
        return post("application/json", body);
    }

    // 요청 바디를 읽어야 캐시에 담긴다. 읽지 않으면 필터가 남길 바디가 없다.
    private FilterChain respond(int status, String body) {
        return (req, res) -> {
            req.getInputStream().readAllBytes();
            ((HttpServletResponse) res).setStatus(status);
            res.setContentType("application/json");
            res.getWriter().write(body);
            res.getWriter().flush();
        };
    }

    private FilterChain markedExpected(FilterChain inner) {
        return (req, res) -> {
            AccessLogSignal.markExpected(req);
            inner.doFilter(req, res);
        };
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, FilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    // --- 마스킹 -----------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("sensitiveKeys")
    void JSON_바디의_민감_키는_값이_가려진다(String key) throws Exception {
        // given
        MockHttpServletRequest request = jsonPost("{\"" + key + "\":\"" + SECRET + "\"}");

        // when
        run(request, respond(400, "{}"));

        // then
        assertThat(logs.only().getFormattedMessage())
                .contains("\"" + key + "\":\"" + MASKED + "\"")
                .doesNotContain(SECRET);
    }

    /*
     * 폼 바디도 같은 키 목록을 쓴다.
     * 폼 형식 요청은 서블릿이 파라미터로 먼저 가져가 바디 캐시가 비는 경우가 있어서,
     * 키=값 문자열을 text/plain 으로 보내 정규식만 확인한다.
     */
    @ParameterizedTest
    @MethodSource("sensitiveKeys")
    void 키_등호_값_바디의_민감_키는_값이_가려진다(String key) throws Exception {
        // given
        MockHttpServletRequest request = post("text/plain", "memo=1&" + key + "=" + SECRET);

        // when
        run(request, respond(400, "{}"));

        // then
        assertThat(logs.only().getFormattedMessage())
                .contains("&" + key + "=" + MASKED)
                .doesNotContain(SECRET);
    }

    @Test
    void 키_이름의_대소문자가_달라도_가려지고_키_표기는_그대로_남는다() throws Exception {
        // given
        MockHttpServletRequest request = jsonPost("{\"PaymentKey\":\"" + SECRET + "\"}");

        // when
        run(request, respond(400, "{}"));

        // then
        assertThat(logs.only().getFormattedMessage())
                .contains("\"PaymentKey\":\"" + MASKED + "\"")
                .doesNotContain(SECRET);
    }

    @Test
    void 응답_바디의_민감_키도_가려진다() throws Exception {
        // given
        MockHttpServletRequest request = jsonPost("{}");

        // when
        run(request, respond(400, "{\"accessToken\":\"" + SECRET + "\"}"));

        // then
        assertThat(logs.only().getFormattedMessage())
                .contains("resBody=\"{\"accessToken\":\"" + MASKED + "\"}\"")
                .doesNotContain(SECRET);
    }

    @Test
    void 값이_빈_문자열이면_가리지_않고_그대로_둔다() throws Exception {
        // given
        MockHttpServletRequest request = jsonPost("{\"password\":\"\"}");

        // when
        run(request, respond(400, "{}"));

        // then
        assertThat(logs.only().getFormattedMessage())
                .contains("\"password\":\"\"")
                .doesNotContain(MASKED);
    }

    @Test
    void 민감하지_않은_키의_값은_그대로_남는다() throws Exception {
        // given
        MockHttpServletRequest request = jsonPost("{\"nickname\":\"lunch\",\"memberId\":\"7\"}");

        // when
        run(request, respond(400, "{}"));

        // then
        assertThat(logs.only().getFormattedMessage())
                .contains("\"nickname\":\"lunch\"")
                .contains("\"memberId\":\"7\"")
                .doesNotContain(MASKED);
    }

    @Test
    void 키가_달라도_본문_안의_이메일은_부분_마스킹된다() throws Exception {
        // given
        MockHttpServletRequest request = jsonPost("{\"memo\":\"contact abcdef@test.com please\"}");

        // when
        run(request, respond(400, "{}"));

        // then
        assertThat(logs.only().getFormattedMessage())
                .contains("ab****@test.com")
                .doesNotContain("abcdef@test.com");
    }

    @Test
    void 키가_달라도_본문_안의_전화번호는_부분_마스킹된다() throws Exception {
        // given
        MockHttpServletRequest request = jsonPost("{\"memo\":\"call 010-1234-5678 please\"}");

        // when
        run(request, respond(400, "{}"));

        // then
        assertThat(logs.only().getFormattedMessage())
                .contains("010****5678")
                .doesNotContain("1234");
    }

    /*
     * 알려진 한계를 고정한다. 정규식이 따옴표로 감싼 문자열 값만 찾아서 숫자 값은 가려지지 않는다.
     * 숫자 값도 가리도록 고치면 이 테스트의 기대값을 함께 바꾼다.
     */
    @Test
    void 숫자_값인_좌표는_지금은_가려지지_않는다() throws Exception {
        // given
        MockHttpServletRequest request = jsonPost("{\"latitude\":37.5665,\"longitude\":126.978}");

        // when
        run(request, respond(400, "{}"));

        // then
        assertThat(logs.only().getFormattedMessage())
                .contains("\"latitude\":37.5665")
                .contains("\"longitude\":126.978");
    }

    // --- 로그 수준과 바디 포함 ------------------------------------------------------

    @Test
    void 정상_응답은_INFO로_상태와_소요시간만_남긴다() throws Exception {
        // given
        MockHttpServletRequest request = jsonPost("{\"password\":\"" + SECRET + "\"}");

        // when
        run(request, respond(200, "{}"));

        // then
        ILoggingEvent event = logs.only();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .startsWith("event=HTTP_ACCESS status=200 durationMs=")
                .doesNotContain("reqBody")
                .doesNotContain(SECRET);
    }

    @Test
    void 클라이언트_오류는_WARN으로_바디까지_남긴다() throws Exception {
        // given
        MockHttpServletRequest request = jsonPost("{\"memo\":\"hello\"}");

        // when
        run(request, respond(400, "{\"code\":\"COMMON-002\"}"));

        // then
        ILoggingEvent event = logs.only();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .startsWith("event=HTTP_ACCESS status=400 durationMs=")
                .contains("reqBody=\"{\"memo\":\"hello\"}\"")
                .contains("resBody=\"{\"code\":\"COMMON-002\"}\"");
    }

    @Test
    void 서버_오류는_ERROR로_바디까지_남긴다() throws Exception {
        // given
        MockHttpServletRequest request = jsonPost("{\"memo\":\"hello\"}");

        // when
        run(request, respond(500, "{\"code\":\"COMMON-001\"}"));

        // then
        ILoggingEvent event = logs.only();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).contains("status=500").contains("reqBody=");
    }

    @Test
    void DEBUG가_켜져_있으면_정상_응답도_바디까지_남긴다() throws Exception {
        // given
        logs.level(Level.DEBUG);
        MockHttpServletRequest request = jsonPost("{\"password\":\"" + SECRET + "\"}");

        // when
        run(request, respond(200, "{\"ok\":\"yes\"}"));

        // then
        ILoggingEvent event = logs.only();
        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(event.getFormattedMessage())
                .contains("reqBody=\"{\"password\":\"" + MASKED + "\"}\"")
                .contains("resBody=\"{\"ok\":\"yes\"}\"")
                .doesNotContain(SECRET);
    }

    @Test
    void 예상된_4xx는_INFO_수준에서는_아무것도_남기지_않는다() throws Exception {
        // given
        MockHttpServletRequest request = jsonPost("{\"memo\":\"hello\"}");

        // when
        run(request, markedExpected(respond(409, "{\"code\":\"COUPON-001\"}")));

        // then
        assertThat(logs.events()).isEmpty();
    }

    @Test
    void 예상된_4xx는_DEBUG_수준에서만_바디까지_남긴다() throws Exception {
        // given
        logs.level(Level.DEBUG);
        MockHttpServletRequest request = jsonPost("{\"memo\":\"hello\"}");

        // when
        run(request, markedExpected(respond(409, "{\"code\":\"COUPON-001\"}")));

        // then
        ILoggingEvent event = logs.only();
        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(event.getFormattedMessage()).contains("status=409").contains("reqBody=");
    }

    @Test
    void 바디가_없는_GET의_4xx도_WARN으로_남긴다() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/test");

        // when
        run(request, respond(404, "{}"));

        // then
        ILoggingEvent event = logs.only();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("reqBody=\"\"");
    }

    @Test
    void 이천_자를_넘는_바디는_잘라서_남긴다() throws Exception {
        // given
        MockHttpServletRequest request = post("text/plain", "a".repeat(3000));

        // when
        run(request, respond(400, "{}"));

        // then
        assertThat(logs.only().getFormattedMessage())
                .contains("...(truncated)")
                .doesNotContain("a".repeat(2001));
    }

    // --- 응답 전달과 제외 대상 --------------------------------------------------------

    @Test
    void 바디를_캐싱해도_클라이언트에는_원래_응답이_그대로_나간다() throws Exception {
        // given
        MockHttpServletRequest request = jsonPost("{}");

        // when
        MockHttpServletResponse response = run(request, respond(400, "{\"code\":\"COMMON-002\"}"));

        // then
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"COMMON-002\"}");
    }

    @Test
    void 텍스트가_아닌_Content_Type은_건너뛰고_아무것도_남기지_않는다() throws Exception {
        // given
        MockHttpServletRequest request = post("image/png", "binary-ish");

        // when
        MockHttpServletResponse response = run(request, respond(500, "{}"));

        // then
        assertThat(logs.events()).isEmpty();
        assertThat(response.getStatus()).isEqualTo(500);
    }

    @Test
    void 스트리밍_경로는_건너뛰고_아무것도_남기지_않는다() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/notifications/stream");

        // when
        run(request, respond(500, "{}"));

        // then
        assertThat(logs.events()).isEmpty();
    }
}
