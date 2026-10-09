package com.launchcatch.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.launchcatch.auth.ApiSecurityDefaults;
import com.launchcatch.auth.AuthCookieFactory;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.SecurityConfig;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.exception.AuthExceptionHandler;
import com.launchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.global.exception.GlobalExceptionHandler;
import com.launchcatch.owner.config.OwnerSecurityConfig;
import com.launchcatch.owner.controller.OwnerTokenRefreshController;
import com.launchcatch.owner.dto.OwnerLoginResponse;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.service.OwnerTokenRefreshResult;
import com.launchcatch.owner.service.OwnerTokenRefreshService;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringBootTest(classes = OwnerTokenRefreshSecurityIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"jwt.cookie.secure=true", "app.cors.allowed-origins=https://owner.example.com"})
class OwnerTokenRefreshSecurityIntegrationTest {
    private static final String SECRET = "test-only-owner-refresh-secret-at-least-32-bytes";
    @Autowired private WebApplicationContext context;
    @Autowired private OwnerTokenRefreshService service;
    @Autowired private JwtTokenProvider jwt;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(service);
        when(service.refresh("valid-refresh")).thenReturn(new OwnerTokenRefreshResult(new OwnerLoginResponse(
                "owner@example.com", Role.OWNER, OwnerStatus.ACTIVE, false),
                jwt.createAccessToken(7L, Role.OWNER), "new-refresh"));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void 보안체인은_AT없이_RT만으로_재발급을_허용한다() throws Exception {
        var response = mvc.perform(post("/v1/owner/auth/tokens:refresh")
                        .cookie(new Cookie("refreshToken", "valid-refresh")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn().getResponse();
        assertThat(response.getCookie("accessToken").getSecure()).isTrue();
        assertThat(response.getCookie("refreshToken").getSecure()).isTrue();
        assertThat(jwt.getRole(response.getCookie("accessToken").getValue())).isEqualTo(Role.OWNER);
    }

    @Test
    void 만료된_AT가_동봉되어도_재발급할_수_있다() throws Exception {
        var expiredJwt = new JwtTokenProvider(SECRET, 1800000, 86400000, 1209600000, 1209600000,
                Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneId.of("Asia/Seoul")));
        mvc.perform(post("/v1/owner/auth/tokens:refresh").cookie(
                        new Cookie("refreshToken", "valid-refresh"),
                        new Cookie("accessToken", expiredJwt.createAccessToken(7L, Role.OWNER))))
                .andExpect(status().isOk());
    }

    @Test
    void 쿠키가_없으면_보안체인에서_AT를_요구하는_대신_AUTH003을_반환한다() throws Exception {
        when(service.refresh(null)).thenThrow(new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID));
        mvc.perform(post("/v1/owner/auth/tokens:refresh"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-003"));
    }

    @Test
    void 재발급_경로는_POST외의_메서드를_허용하지_않는다() throws Exception {
        mvc.perform(get("/v1/owner/auth/tokens:refresh").cookie(new Cookie("accessToken", jwt.createAccessToken(7L, Role.OWNER))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void 로그아웃_API는_이번_구현에서_열지_않는다() throws Exception {
        mvc.perform(delete("/v1/owner/auth/tokens")
                        .cookie(new Cookie("accessToken", jwt.createAccessToken(7L, Role.OWNER))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({OwnerSecurityConfig.class, SecurityConfig.class, ApiSecurityDefaults.class,
            AuthCookieFactory.class, AuthExceptionHandler.class, GlobalExceptionHandler.class,
            OwnerTokenRefreshController.class})
    static class TestConfig {
        @Bean OwnerTokenRefreshService service() { return mock(OwnerTokenRefreshService.class); }
        @Bean StringRedisTemplate redisTemplate() { return mock(StringRedisTemplate.class); }
        @Bean AccessTokenValidAfterRepository cutoff() {
            var repository = mock(AccessTokenValidAfterRepository.class);
            when(repository.isValidAfter(any(), any(), any())).thenReturn(true);
            return repository;
        }
        @Bean JwtTokenProvider jwt() {
            return new JwtTokenProvider(SECRET, 1800000, 86400000, 1209600000, 1209600000,
                    Clock.system(ZoneId.of("Asia/Seoul")));
        }
    }
}
