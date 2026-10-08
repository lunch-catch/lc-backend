package com.launchcatch.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import com.launchcatch.admin.config.AdminRegistrationSecurityConfig;
import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.admin.service.AdminRegistrationService;
import com.launchcatch.auth.ApiSecurityDefaults;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthExceptionHandler;
import com.launchcatch.auth.jwt.AccessTokenCutoffVerifier;
import com.launchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.global.exception.GlobalExceptionHandler;
import com.launchcatch.ops.contract.AuditLogWriter;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

// 실제 보안 체인과 MVC·서비스를 연결하고, 외부 저장소만 테스트 더블로 대체한다.
@SpringBootTest(classes = AdminRegistrationSecurityIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class AdminRegistrationSecurityIntegrationTest {
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 10, 8, 10, 0);
    private static final String BODY = """
            {"loginId":"admin01","initialPassword":"Initial123!","name":"관리자","role":"ADMIN"}
            """;
    @Autowired private WebApplicationContext context;
    @Autowired private JwtTokenProvider tokens;
    @Autowired private AccessTokenValidAfterRepository cutoff;
    @Autowired private AdminRepository admins;
    @Autowired private PasswordEncoder encoder;
    @Autowired private AuditLogWriter audit;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private FilterChainProxy proxy;
    @Autowired @Qualifier("adminRegistrationSecurityFilterChain") private SecurityFilterChain registrationChain;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(tokens, cutoff, admins, encoder, audit, transactions);
        when(tokens.validateToken("token")).thenReturn(true);
        when(tokens.getId("token")).thenReturn(1L);
        when(tokens.getRole("token")).thenReturn(Role.SUPER_ADMIN);
        when(tokens.getIssuedAt("token")).thenReturn(ISSUED_AT);
        when(cutoff.isValidAfter(Role.SUPER_ADMIN, 1L, ISSUED_AT)).thenReturn(true);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void 넓은_관리자_체인보다_발급_체인이_먼저_선택된다() {
        var request = new MockHttpServletRequest(context.getServletContext(), "POST", "/v1/admin/admins");
        request.setServletPath("/v1/admin/admins");
        assertThat(proxy.getFilterChains().stream().filter(chain -> chain.matches(request)).findFirst())
                .contains(registrationChain);
        var other = new MockHttpServletRequest(context.getServletContext(), "POST", "/v1/admin/auth/tokens");
        other.setServletPath("/v1/admin/auth/tokens");
        assertThat(registrationChain.matches(other)).isFalse();
    }

    @Test
    void Redis_장애는_503_AUTH002와_RetryAfter로_응답하고_저장하지_않는다() throws Exception {
        when(cutoff.isValidAfter(Role.SUPER_ADMIN, 1L, ISSUED_AT))
                .thenThrow(new DataAccessResourceFailureException("Redis unavailable"));
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            mvc.perform(post("/v1/admin/admins").cookie(new Cookie("accessToken", "token"))
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("AUTH-002"))
                    .andExpect(header().string("Retry-After", "1"));
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("AUTH-002");
            });
            verifyNoInteractions(admins, encoder, audit, transactions);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void 일반_관리자는_넓은_체인이_허용하더라도_발급에서_403이다() throws Exception {
        when(tokens.getRole("token")).thenReturn(Role.ADMIN);
        when(cutoff.isValidAfter(Role.ADMIN, 1L, ISSUED_AT)).thenReturn(true);
        mvc.perform(post("/v1/admin/admins").cookie(new Cookie("accessToken", "token"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
        verifyNoInteractions(admins, encoder, audit, transactions);
    }

    @Test
    void 발급_시각_누락은_401이다() throws Exception {
        when(tokens.getIssuedAt("token")).thenReturn(null);
        mvc.perform(post("/v1/admin/admins").cookie(new Cookie("accessToken", "token"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
        verifyNoInteractions(admins, encoder, audit, transactions);
    }

    @Test
    void 정상_폐기_확인과_현재_권한_확인_후_201로_발급한다() throws Exception {
        when(admins.findByIdForUpdate(1L)).thenReturn(Optional.of(
                Admin.register("issuer", "hash", "발급자", Role.SUPER_ADMIN)));
        when(encoder.encode("Initial123!")).thenReturn("hash");
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(admins.saveAndFlush(any(Admin.class))).thenAnswer(i -> i.getArgument(0));
        mvc.perform(post("/v1/admin/admins").cookie(new Cookie("accessToken", "token"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("SUCCESS"));
        verify(audit).write(1L, "ADMIN_ACCOUNT_CREATE", "admin01", "role=ADMIN");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @ComponentScan(basePackages = "com.launchcatch.admin.controller")
    @Import({AdminRegistrationSecurityConfig.class, ApiSecurityDefaults.class,
            GlobalExceptionHandler.class, AuthExceptionHandler.class})
    static class TestConfig {
        @Bean JwtTokenProvider tokens() { return mock(JwtTokenProvider.class); }
        @Bean AccessTokenValidAfterRepository cutoff() { return mock(AccessTokenValidAfterRepository.class); }
        @Bean StringRedisTemplate redisTemplate() { return mock(StringRedisTemplate.class); }
        @Bean CorsConfigurationSource corsConfigurationSource() { return request -> null; }
        @Bean AdminRepository admins() { return mock(AdminRepository.class); }
        @Bean PasswordEncoder encoder() { return mock(PasswordEncoder.class); }
        @Bean AuditLogWriter audit() { return mock(AuditLogWriter.class); }
        @Bean PlatformTransactionManager transactions() { return mock(PlatformTransactionManager.class); }
        @Bean AccessTokenCutoffVerifier cutoffVerifier(AccessTokenValidAfterRepository cutoff) {
            return new AccessTokenCutoffVerifier(cutoff);
        }
        @Bean AdminRegistrationService registration(AdminRepository admins, AuditLogWriter audit,
                PasswordEncoder encoder, PlatformTransactionManager transactions, AccessTokenCutoffVerifier verifier) {
            return new AdminRegistrationService(admins, audit, encoder, transactions, verifier);
        }
        @Bean
        @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
        SecurityFilterChain broadAdminChain(HttpSecurity http, ApiSecurityDefaults defaults) throws Exception {
            return defaults.apply(http).securityMatcher("/v1/admin/**")
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }
    }
}
