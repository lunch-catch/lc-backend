package com.launchcatch.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockAsyncContext;
import org.slf4j.MDC;

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

    @Test
    void 비동기_재디스패치와_완료_통지가_와도_접근_로그는_한번만_남긴다() throws Exception {
        MockHttpServletRequest request = jsonPost("{}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setAsyncSupported(true);
        filter.doFilter(request, response, (req, res) -> req.startAsync(req, res));
        MockAsyncContext context = (MockAsyncContext) request.getAsyncContext();
        assertThat(logs.events()).isEmpty();

        request.setDispatcherType(DispatcherType.ASYNC);
        request.setAsyncStarted(false);
        filter.doFilter(request, response, respond(201, "{\"issued\":true}"));
        context.complete();

        assertThat(logs.events()).hasSize(1);
        assertThat(logs.only().getFormattedMessage()).contains("status=201");
        assertThat(response.getContentAsString()).isEqualTo("{\"issued\":true}");
    }

    @Test
    void 재디스패치_없이_끊긴_비동기_요청도_원래_추적ID로_기록한다() throws Exception {
        MockHttpServletRequest request = jsonPost("{\"password\":\"secret-value\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setAsyncSupported(true);
        try {
            MDC.put("traceId", "request-trace");
            filter.doFilter(request, response, (req, res) -> req.startAsync(req, res));
            MockAsyncContext context = (MockAsyncContext) request.getAsyncContext();
            MDC.put("traceId", "completion-thread");
            for (var listener : context.getListeners()) {
                listener.onError(new AsyncEvent(context, new java.io.IOException("secret-value")));
            }
            context.complete();
            context.complete();

            assertThat(logs.events()).hasSize(1);
            assertThat(logs.only().getFormattedMessage()).contains("reason=ASYNC_ERROR")
                    .doesNotContain("secret-value");
            assertThat(logs.only().getMDCPropertyMap()).containsEntry("traceId", "request-trace");
            assertThat(MDC.get("traceId")).isEqualTo("completion-thread");
        } finally {
            MDC.clear();
        }
    }

    @Test
    void 계정_발급_본문은_로깅_대상에서_제외한다() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/admin/admins");
        request.setContentType("application/json");
        assertThat(filter.shouldNotFilter(request)).isTrue();
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
                "initialPassword", "currentPassword", "newPassword", "representativeName", "fid",
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

    @Test
    void 숫자_값인_좌표도_가린다() throws Exception {
        // given
        MockHttpServletRequest request = jsonPost("{\"latitude\":37.5665,\"longitude\":126.978}");

        // when
        run(request, respond(400, "{}"));

        // then
        assertThat(logs.only().getFormattedMessage())
                .contains("\"latitude\":\"" + MASKED + "\"")
                .contains("\"longitude\":\"" + MASKED + "\"")
                .doesNotContain("37.5665", "126.978");
    }

    static Stream<Arguments> numericCoordinates() {
        return Stream.of("lat", "lng", "latitude", "longitude", "LATITUDE")
                .flatMap(key -> Stream.of("0", "-0", "37", "-37.5665", "126.978", "3.75665e1", "1.26978E+2", "-1.2e-3")
                        .map(value -> Arguments.of(key, value)));
    }

    @ParameterizedTest
    @MethodSource("numericCoordinates")
    void 정수_음수_소수_지수_좌표는_요청과_응답_로그에서_가린다(String key, String value) throws Exception {
        String body = "{\"nested\":[{\"" + key + "\" : " + value + ",\"count\":7}]}";
        MockHttpServletRequest request = jsonPost(body);

        MockHttpServletResponse response = run(request, respond(400, body));

        assertThat(logs.only().getFormattedMessage()).contains("\"" + key + "\" : \"" + MASKED + "\"", "\"count\":7")
                .doesNotContain("\"" + key + "\" : " + value);
        assertThat(response.getContentAsString()).isEqualTo(body);
        assertThat(request.getContentAsByteArray()).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void DEBUG_성공_로그에서도_좌표를_가리고_실제_응답은_유지한다() throws Exception {
        logs.level(Level.DEBUG);
        String body = "{\"latitude\":37.5665,\"longitude\":126.978}";

        MockHttpServletResponse response = run(jsonPost(body), respond(200, body));

        assertThat(logs.only().getFormattedMessage()).contains(MASKED).doesNotContain("37.5665", "126.978");
        assertThat(response.getContentAsString()).isEqualTo(body);
    }

    @Test
    void 없는_좌표는_null로_유지한다() throws Exception {
        String body = "{\"latitude\":null,\"longitude\":null}";

        run(jsonPost(body), respond(500, body));

        assertThat(logs.only().getFormattedMessage()).contains(body).doesNotContain(MASKED);
    }

    @Test
    void 긴_숫자_좌표도_길이를_자르기_전에_가린다() throws Exception {
        String body = "{\"latitude\":" + "123456789".repeat(350) + "}";

        run(jsonPost(body), respond(400, body));

        assertThat(logs.only().getFormattedMessage()).contains("\"latitude\":\"" + MASKED + "\"")
                .doesNotContain("123456789");
    }

    @Test
    void 캐시_끝에서_지수_숫자가_잘려도_좌표의_앞부분을_남기지_않는다() throws Exception {
        String body = "{\"latitude\":" + "1".repeat(8179) + "e+123}";

        run(jsonPost(body), respond(400, "{}"));

        assertThat(logs.only().getFormattedMessage()).contains("\"latitude\":\"" + MASKED + "\"")
                .doesNotContain("111111111");
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
