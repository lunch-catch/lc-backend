package com.launchcatch.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.launchcatch.admin.config.AdminLoginSecurityConfig;
import com.launchcatch.admin.config.AdminLoginAuditFilter;
import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.auth.ApiSecurityDefaults;
import com.launchcatch.auth.AuthCookieFactory;
import com.launchcatch.auth.PasswordEncoderConfig;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.SecurityConfig;
import com.launchcatch.auth.exception.AuthExceptionHandler;
import com.launchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.global.exception.GlobalExceptionHandler;
import com.launchcatch.global.logging.HttpBodyLoggingFilter;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.FilterType;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

// 실제 보안 설정, MVC와 로그인 서비스를 연결하고 DB 및 Redis 접근만 테스트 더블로 대체한다.
@SpringBootTest(classes = AdminLoginSecurityIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"jwt.cookie.secure=true", "app.cors.allowed-origins=https://admin.example.com"})
class AdminLoginSecurityIntegrationTest {
    private static final String BODY = """
            {"loginId":"admin01","password":"Freshman!2026"}
            """;
    @Autowired private WebApplicationContext context;
    @Autowired private AdminRepository admins;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private PasswordEncoder encoder;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private HttpBodyLoggingFilter loggingFilter;
    @Autowired private AdminLoginAuditFilter auditFilter;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(admins, refreshTokens, transactions);
        Admin admin = Admin.register("admin01", encoder.encode("Freshman!2026"), "관리자", Role.SUPER_ADMIN);
        ReflectionTestUtils.setField(admin, "id", 1L);
        when(admins.findByLoginId("admin01")).thenReturn(Optional.of(admin));
        when(admins.findByIdForUpdate(1L)).thenReturn(Optional.of(admin));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus(), new SimpleTransactionStatus());
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(loggingFilter, auditFilter)
                .apply(springSecurity()).build();
    }

    @Test
    void 인증없이_로그인하고_실제_JWT와_명세의_응답을_받는다() throws Exception {
        var result = mvc.perform(post("/v1/admin/auth/tokens").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.adminId").value(1))
                .andExpect(jsonPath("$.data.role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn();
        Cookie access = result.getResponse().getCookie("accessToken");
        Cookie refresh = result.getResponse().getCookie("refreshToken");
        assertThat(access.isHttpOnly()).isTrue();
        assertThat(access.getSecure()).isTrue();
        assertThat(access.getMaxAge()).isEqualTo(1800);
        assertThat(jwt.validateToken(access.getValue())).isTrue();
        assertThat(jwt.getRole(access.getValue())).isEqualTo(Role.SUPER_ADMIN);
        assertThat(jwt.getIssuedAt(access.getValue())).isNotNull();
        assertThat(refresh.getPath()).isEqualTo("/v1/admin/auth/");
        assertThat(refresh.getMaxAge()).isEqualTo(86400);
    }

    @Test
    void 없는_계정은_AUTH001이며_쿠키를_발급하지_않는다() throws Exception {
        when(admins.findByLoginId("admin01")).thenReturn(Optional.empty());
        mvc.perform(post("/v1/admin/auth/tokens").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-001"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @ParameterizedTest
    @CsvSource({"73, 0", "1, 24", "0, 25"})
    void UTF8_72바이트_초과_비밀번호는_401_AUTH001이며_쿠키를_발급하지_않는다(
            int asciiLength, int koreanLength) throws Exception {
        String password = "a".repeat(asciiLength) + "가".repeat(koreanLength);
        String body = "{\"loginId\":\"admin01\",\"password\":\"" + password + "\"}";
        mvc.perform(post("/v1/admin/auth/tokens").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-001"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        org.mockito.Mockito.verifyNoInteractions(refreshTokens);
        assertThat(admins.findByLoginId("admin01").orElseThrow().getRefreshTokenHash()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"72, 0", "0, 24"})
    void UTF8_72바이트_비밀번호는_일치하면_로그인할_수_있다(
            int asciiLength, int koreanLength) throws Exception {
        String password = "a".repeat(asciiLength) + "가".repeat(koreanLength);
        Admin admin = Admin.register("admin01", encoder.encode(password), "관리자", Role.SUPER_ADMIN);
        ReflectionTestUtils.setField(admin, "id", 1L);
        when(admins.findByLoginId("admin01")).thenReturn(Optional.of(admin));
        when(admins.findByIdForUpdate(1L)).thenReturn(Optional.of(admin));
        String body = "{\"loginId\":\"admin01\",\"password\":\"" + password + "\"}";
        mvc.perform(post("/v1/admin/auth/tokens").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(header().exists(HttpHeaders.SET_COOKIE));
    }

    @Test
    void 필수_값이_없으면_400이며_쿠키를_발급하지_않는다() throws Exception {
        mvc.perform(post("/v1/admin/auth/tokens").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void DB_백업_장애는_503_AUTH002_RetryAfter_ERROR이며_쿠키를_발급하지_않는다() throws Exception {
        when(admins.findByIdForUpdate(1L)).thenThrow(new DataAccessResourceFailureException("DB unavailable"));
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            mvc.perform(post("/v1/admin/auth/tokens").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH-002"))
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("AUTH-002");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void Redis_저장_장애시_DB_백업후_로그인_성공을_유지한다() throws Exception {
        doThrow(new DataAccessResourceFailureException("Redis unavailable"))
                .when(refreshTokens).saveIfNewer(any(), eq(1L), eq(Role.SUPER_ADMIN), eq(true), any(), org.mockito.ArgumentMatchers.anyLong());
        mvc.perform(post("/v1/admin/auth/tokens").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(header().exists(HttpHeaders.SET_COOKIE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/admin/auth/tokens:refresh", "/v1/admin/auth/unknown"})
    void 아직_구현하지_않은_인증_경로는_유효한_토큰으로도_접근할_수_없다(String path) throws Exception {
        mvc.perform(post(path).cookie(new Cookie("accessToken", jwt.createAccessToken(1L, Role.SUPER_ADMIN))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 로그아웃_경로도_유효한_토큰으로_접근할_수_없다() throws Exception {
        mvc.perform(delete("/v1/admin/auth/tokens")
                        .cookie(new Cookie("accessToken", jwt.createAccessToken(1L, Role.SUPER_ADMIN))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 깨진_JSON의_비밀번호가_예외_로그에_남지_않는다() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            mvc.perform(post("/v1/admin/auth/tokens").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"loginId\":\"admin01\",\"password\":\"SensitivePassword!\" BROKEN}"))
                    .andExpect(status().isBadRequest());
            assertThat(appender.list).isNotEmpty().allSatisfy(event ->
                    assertThat(event.getFormattedMessage()).doesNotContain("SensitivePassword!"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void 로그인_성공과_실패를_본문이나_토큰없이_감사_로그로_남긴다() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(AdminLoginAuditFilter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            mvc.perform(post("/v1/admin/auth/tokens").contentType(MediaType.APPLICATION_JSON).content(BODY)
                            .with(request -> { request.setRemoteAddr("192.0.2.10"); return request; }))
                    .andExpect(status().isOk());
            when(admins.findByLoginId("admin01")).thenReturn(Optional.empty());
            mvc.perform(post("/v1/admin/auth/tokens").contentType(MediaType.APPLICATION_JSON).content(BODY)
                            .with(request -> { request.setRemoteAddr("192.0.2.10"); return request; }))
                    .andExpect(status().isUnauthorized());
            assertThat(appender.list).hasSize(2).allSatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains("event=ADMIN_LOGIN", "occurredAt=",
                            "clientIp=192.0.2.10").doesNotContain("Freshman!2026", "password", "refreshToken"));
            assertThat(appender.list.get(0).getFormattedMessage()).contains("success=true", "status=200");
            assertThat(appender.list.get(1).getFormattedMessage()).contains("success=false", "status=401");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "INACTIVE", "ROLE_CHANGED"})
    void DB_백업후_계정이_사용불가이면_AUTH001이며_쿠키를_발급하지_않는다(String change) throws Exception {
        Admin admin = admins.findByLoginId("admin01").orElseThrow();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(admins.findByIdForUpdate(1L)).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                return Optional.of(admin);
            }
            if ("MISSING".equals(change)) {
                return Optional.empty();
            }
            if ("INACTIVE".equals(change)) {
                ReflectionTestUtils.setField(admin, "status", com.launchcatch.admin.entity.AdminStatus.DELETED);
            } else {
                ReflectionTestUtils.setField(admin, "role", Role.ADMIN);
            }
            return Optional.of(admin);
        });
        mvc.perform(post("/v1/admin/auth/tokens").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-001"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        org.mockito.Mockito.verifyNoInteractions(refreshTokens);
        org.mockito.Mockito.verify(admins).clearRefreshTokenIfMatches(eq(1L), any(), any());
    }
    @ParameterizedTest
    @ValueSource(strings = {"DB_READ", "TRANSACTION_BEGIN", "TRANSACTION_COMMIT"})
    void 최종_DB_검사_장애는_AUTH002이며_쿠키를_발급하지_않는다(String failure) throws Exception {
        Admin admin = admins.findByLoginId("admin01").orElseThrow();
        if ("DB_READ".equals(failure)) {
            when(admins.findByIdForUpdate(1L)).thenReturn(Optional.of(admin))
                    .thenThrow(new DataAccessResourceFailureException("final DB read failed"));
        } else if ("TRANSACTION_BEGIN".equals(failure)) {
            when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus(), new SimpleTransactionStatus())
                    .thenThrow(new org.springframework.transaction.CannotCreateTransactionException("final transaction failed"))
                    .thenReturn(new SimpleTransactionStatus());
        } else {
            org.mockito.Mockito.doNothing()
                    .doThrow(new org.springframework.transaction.TransactionSystemException("final commit failed"))
                    .doNothing().when(transactions).commit(any());
        }
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            mvc.perform(post("/v1/admin/auth/tokens").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH-002"))
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
            org.mockito.Mockito.verify(admins).clearRefreshTokenIfMatches(eq(1L), any(), any());
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("AUTH-002");
            });
            if (!"TRANSACTION_COMMIT".equals(failure)) {
                org.mockito.Mockito.verifyNoInteractions(refreshTokens);
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableTransactionManagement
    @ComponentScan(basePackages = "com.launchcatch.admin", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "com\\.launchcatch\\.admin\\.(controller|service)\\.AdminLogin(Controller|Service|TransactionService)"))
    @Import({AdminLoginSecurityConfig.class, SecurityConfig.class, ApiSecurityDefaults.class,
            PasswordEncoderConfig.class, AuthCookieFactory.class, AuthExceptionHandler.class,
            GlobalExceptionHandler.class, HttpBodyLoggingFilter.class, AdminLoginAuditFilter.class})
    static class TestConfig {
        @Bean AdminRepository admins() { return mock(AdminRepository.class); }
        @Bean RefreshTokenRepository refreshTokens() { return mock(RefreshTokenRepository.class); }
        @Bean AccessTokenValidAfterRepository cutoff() {
            var repository = mock(AccessTokenValidAfterRepository.class);
            when(repository.isValidAfter(any(), any(), any())).thenReturn(true);
            return repository;
        }
        @Bean StringRedisTemplate redisTemplate() { return mock(StringRedisTemplate.class); }
        @Bean PlatformTransactionManager transactions() { return mock(PlatformTransactionManager.class); }
        @Bean Clock clock() {
            // 실제 JWT 파서는 시스템 시각으로 만료를 검사하므로 이 연결 테스트도 현재 시각을 사용한다.
            return Clock.system(ZoneId.of("Asia/Seoul"));
        }
        @Bean JwtTokenProvider jwt(Clock clock) {
            return new JwtTokenProvider("test-only-signing-secret-with-at-least-32-bytes", 1800000,
                    86400000, 1209600000, 1209600000, clock);
        }
    }
}
