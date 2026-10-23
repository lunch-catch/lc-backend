package com.launchcatch.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminLoginAuditFilterTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final AdminLoginAuditFilter filter = new AdminLoginAuditFilter(clock);
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/admin/auth/tokens");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AdminLoginAuditFilter.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        logs.start();
        logger.addAppender(logs);
        request.setRemoteAddr("192.0.2.1");
        request.setContent("{\"password\":\"SensitivePassword!\"}".getBytes(StandardCharsets.UTF_8));
        request.setCookies(new Cookie("adminAccessToken", "SensitiveAccessToken"),
                new Cookie("refreshToken", "SensitiveRefreshToken"));
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logger.setLevel(originalLevel);
        logs.stop();
        MDC.clear();
    }

    @ParameterizedTest
    @CsvSource({
            "200,ADMIN_LOGIN_SUCCESS,INFO", "400,ADMIN_LOGIN_FAILED,INFO",
            "401,ADMIN_LOGIN_FAILED,INFO", "429,ADMIN_LOGIN_FAILED,INFO", "503,ADMIN_LOGIN_FAILED,ERROR"
    })
    void 최종_결과에_따라_이벤트와_상태_레벨을_기록한다(int status, String eventName, String level) throws Exception {
        filter.doFilter(request, response, (req, res) -> response.setStatus(status));
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.toLevel(level));
            assertThat(event.getFormattedMessage()).contains("event=" + eventName, "httpStatus=" + status, "durationMs=")
                    .doesNotContain("occurredAt=", "clientIp=", "success=", " status=")
                    .doesNotContain("SensitivePassword!", "SensitiveAccessToken", "SensitiveRefreshToken");
        });
        assertThat(request.getInputStream().readAllBytes()).isEqualTo(request.getContentAsByteArray());
    }

    @Test
    void 필터_체인_예외는_200으로_오인하지_않고_실패로_기록한다() {
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new ServletException("SensitivePassword!");
        })).isInstanceOf(ServletException.class);
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("event=ADMIN_LOGIN_FAILED", "httpStatus=500")
                    .doesNotContain("SensitivePassword!");
        });
    }

    @Test
    void 전달_IP를_로그_메시지에_중복해서_기록하지_않는다() throws Exception {
        request.addHeader("X-Forwarded-For", "198.51.100.10, 203.0.113.20");
        filter.doFilter(request, response, (req, res) -> { });
        assertThat(logs.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("event=ADMIN_LOGIN_SUCCESS")
                        .doesNotContain("clientIp=", "203.0.113.20", "198.51.100.10"));
    }

    @Test
    void 잘못된_전달_IP를_로그에_기록하지_않는다() throws Exception {
        request.addHeader("X-Forwarded-For", "invalid-ip-value");
        filter.doFilter(request, response, (req, res) -> { });
        assertThat(logs.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("event=ADMIN_LOGIN_SUCCESS")
                        .doesNotContain("clientIp=")
                        .doesNotContain("invalid-ip-value"));
    }

    @ParameterizedTest
    @CsvSource({"POST,/v1/admin/auth/tokens:refresh", "DELETE,/v1/admin/auth/tokens", "POST,/v1/auth/tokens"})
    void 관리자_로그인_이외의_요청은_기록하지_않는다(String method, String path) throws Exception {
        request.setMethod(method);
        request.setRequestURI(path);
        filter.doFilter(request, response, (req, res) -> { });
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 로그인_로그는_MDC를_유지하고_본문과_쿠키를_기록하지_않는다() throws Exception {
        MDC.put("traceId", "admin-677-trace");
        MDC.put("clientIp", "192.0.2.1");
        try {
            filter.doFilter(request, response, (req, res) -> { });
            assertThat(logs.list).singleElement().satisfies(event -> {
                assertThat(event.getMDCPropertyMap()).containsEntry("traceId", "admin-677-trace")
                        .containsEntry("clientIp", "192.0.2.1");
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain("Sensitive", "occurredAt=", "clientIp=");
            });
        } finally {
            MDC.clear();
        }
    }
    @Test
    void 공유_해석기로_정한_IP를_기록한_뒤_기존_MDC를_복원한다() throws Exception {
        MDC.put("clientIp", "198.51.100.30");
        MDC.put("traceId", "existing-trace");
        request.addHeader("X-Forwarded-For", "198.51.100.10, 203.0.113.20");

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(logs.list).singleElement().satisfies(event ->
                assertThat(event.getMDCPropertyMap()).containsEntry("clientIp", "203.0.113.20")
                        .containsEntry("traceId", "existing-trace"));
        assertThat(MDC.get("clientIp")).isEqualTo("198.51.100.30");
        assertThat(MDC.get("traceId")).isEqualTo("existing-trace");
    }

    @Test
    void 체인_예외에도_감사_IP를_기록하고_새_MDC값을_남기지_않는다() {
        MDC.clear();
        request.addHeader("X-Forwarded-For", "198.51.100.10, 10.0.0.7");

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new ServletException("SensitivePassword!");
        })).isInstanceOf(ServletException.class);

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getMDCPropertyMap()).containsEntry("clientIp", "10.0.0.7");
            assertThat(event.getFormattedMessage()).contains("event=ADMIN_LOGIN_FAILED", "httpStatus=500")
                    .doesNotContain("SensitivePassword!", "clientIp=");
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertThat(MDC.get("clientIp")).isNull();
    }
}
