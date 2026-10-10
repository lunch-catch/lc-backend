package com.launchcatch.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.launchcatch.auth.*;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.*;
import com.launchcatch.auth.jwt.*;
import com.launchcatch.global.exception.GlobalExceptionHandler;
import com.launchcatch.owner.config.OwnerSecurityConfig;
import com.launchcatch.owner.controller.OwnerLogoutController;
import com.launchcatch.owner.service.OwnerLogoutService;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringBootTest(classes = OwnerLogoutSecurityIntegrationTest.Config.class,
        properties = {"jwt.cookie.secure=true", "app.cors.allowed-origins=https://owner.example.com"})
class OwnerLogoutSecurityIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired OwnerLogoutService service;
    @Autowired JwtTokenProvider jwt;
    @Autowired AccessTokenValidAfterRepository cutoff;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(service, cutoff);
        when(cutoff.isValidAfter(any(), any(), any())).thenReturn(true);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void RT_쿠키없이_로그아웃하고_발급과_같은_Path로_쿠키를_삭제한다() throws Exception {
        var response = mvc.perform(delete("/v1/owner/auth/tokens")
                .cookie(new Cookie("accessToken", jwt.createAccessToken(7L, Role.OWNER))))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andReturn().getResponse();
        verify(service).logout(7L);
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2)
                .anySatisfy(c -> assertThat(c).contains("accessToken=", "Path=/;", "Max-Age=0", "HttpOnly", "Secure", "SameSite=Strict"))
                .anySatisfy(c -> assertThat(c).contains("refreshToken=", "Path=/v1/owner/auth/;", "Max-Age=0", "HttpOnly", "Secure", "SameSite=Strict"));
    }

    @Test
    void AT가_없거나_잘못됐으면_401이다() throws Exception {
        mvc.perform(delete("/v1/owner/auth/tokens")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
        mvc.perform(delete("/v1/owner/auth/tokens").cookie(new Cookie("accessToken", "invalid")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN", "MEMBER"})
    void 다른_역할은_403이다(Role role) throws Exception {
        mvc.perform(delete("/v1/owner/auth/tokens").cookie(new Cookie("accessToken", jwt.createAccessToken(7L, role))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
        verifyNoInteractions(service);
    }

    @Test
    void 차단된_AT는_401이다() throws Exception {
        when(cutoff.isValidAfter(any(), any(), any())).thenReturn(false);
        mvc.perform(delete("/v1/owner/auth/tokens").cookie(new Cookie("accessToken", jwt.createAccessToken(7L, Role.OWNER))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void 폐기_실패시_503이며_성공_쿠키를_내리지_않는다() throws Exception {
        doThrow(new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE)).when(service).logout(7L);
        mvc.perform(delete("/v1/owner/auth/tokens").cookie(new Cookie("accessToken", jwt.createAccessToken(7L, Role.OWNER))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH-002"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Configuration(proxyBeanMethods=false)
    @EnableWebMvc
    @Import({SecurityConfig.class, ApiSecurityDefaults.class, OwnerSecurityConfig.class,
            OwnerLogoutController.class, AuthCookieFactory.class, GlobalExceptionHandler.class, AuthExceptionHandler.class})
    static class Config {
        @Bean org.springframework.data.redis.core.StringRedisTemplate redis() { return mock(org.springframework.data.redis.core.StringRedisTemplate.class); }
        @Bean OwnerLogoutService service() { return mock(OwnerLogoutService.class); }
        @Bean AccessTokenValidAfterRepository cutoff() { return mock(AccessTokenValidAfterRepository.class); }
        @Bean Clock clock() { return Clock.system(ZoneId.of("Asia/Seoul")); }
        @Bean JwtTokenProvider jwt(Clock clock) {
            return new JwtTokenProvider("test-secret-key-that-is-at-least-32-bytes-long-for-hmac", 1800000L, 86400000L, 1209600000L, 1209600000L, clock);
        }
    }
}
