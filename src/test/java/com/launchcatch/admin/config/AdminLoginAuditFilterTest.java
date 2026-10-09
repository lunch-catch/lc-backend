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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminLoginAuditFilterTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final AdminLoginAuditFilter filter = new AdminLoginAuditFilter(clock);
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/admin/auth/tokens");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AdminLoginAuditFilter.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        logs.start();
        logger.addAppender(logs);
        request.setRemoteAddr("192.0.2.1");
        request.setContent("{\"password\":\"SensitivePassword!\"}".getBytes(StandardCharsets.UTF_8));
        request.setCookies(new Cookie("accessToken", "SensitiveAccessToken"),
                new Cookie("refreshToken", "SensitiveRefreshToken"));
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logger.setLevel(originalLevel);
        logs.stop();
    }

    @ParameterizedTest
    @CsvSource({"200,true,INFO", "400,false,WARN", "401,false,WARN", "429,false,WARN", "503,false,ERROR"})
    void 최종_결과에_따라_시각_IP_성공여부만_기록한다(int status, boolean success, String level) throws Exception {
        filter.doFilter(request, response, (req, res) -> response.setStatus(status));
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.toLevel(level));
            assertThat(event.getFormattedMessage()).contains("event=ADMIN_LOGIN", "occurredAt=2026-10-08T12:00",
                    "clientIp=192.0.2.1", "success=" + success, "status=" + status)
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
            assertThat(event.getFormattedMessage()).contains("success=false", "status=500")
                    .doesNotContain("SensitivePassword!");
        });
    }

    @Test
    void 로드_밸런서가_마지막에_붙인_IP를_기록한다() throws Exception {
        request.addHeader("X-Forwarded-For", "198.51.100.10, 203.0.113.20");
        filter.doFilter(request, response, (req, res) -> { });
        assertThat(logs.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("clientIp=203.0.113.20"));
    }

    @Test
    void 잘못된_전달_IP는_접속_IP로_대체한다() throws Exception {
        request.addHeader("X-Forwarded-For", "invalid-ip-value");
        filter.doFilter(request, response, (req, res) -> { });
        assertThat(logs.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("clientIp=192.0.2.1")
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
}
