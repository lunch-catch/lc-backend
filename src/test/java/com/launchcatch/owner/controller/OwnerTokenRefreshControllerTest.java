package com.launchcatch.owner.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.launchcatch.auth.AuthCookieFactory;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.global.exception.GlobalExceptionHandler;
import com.launchcatch.owner.dto.OwnerLoginResponse;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.service.OwnerTokenRefreshResult;
import com.launchcatch.owner.service.OwnerTokenRefreshService;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OwnerTokenRefreshControllerTest {
    private final OwnerTokenRefreshService service = mock(OwnerTokenRefreshService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        when(jwt.getAccessTokenValidityMs()).thenReturn(Duration.ofMinutes(30).toMillis());
        when(jwt.refreshTokenValidityMs(Role.OWNER)).thenReturn(Duration.ofDays(14).toMillis());
        mvc = MockMvcBuilders.standaloneSetup(new OwnerTokenRefreshController(service, new AuthCookieFactory(jwt)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"ONBOARDING", "ACTIVE"})
    void 요청본문과_AT없이_RT_쿠키만으로_재발급한다(OwnerStatus ownerStatus) throws Exception {
        when(service.refresh("old-refresh")).thenReturn(new OwnerTokenRefreshResult(new OwnerLoginResponse(
                "owner@example.com", Role.OWNER, ownerStatus, ownerStatus == OwnerStatus.ACTIVE ? false : null),
                "new-access", "new-refresh"));
        var action = mvc.perform(post("/v1/owner/auth/tokens:refresh").cookie(new Cookie("refreshToken", "old-refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("owner@example.com"))
                .andExpect(jsonPath("$.data.role").value("OWNER"))
                .andExpect(jsonPath("$.data.status").value(ownerStatus.name()));
        if (ownerStatus == OwnerStatus.ACTIVE) {
            action.andExpect(jsonPath("$.data.tutorialViewed").value(false));
        } else {
            action.andExpect(jsonPath("$.data.tutorialViewed").doesNotExist());
        }
        var response = action.andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("new-access", "new-refresh", "old-refresh");
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2)
                .anySatisfy(cookie -> assertThat(cookie).contains("accessToken=new-access", "Path=/;",
                        "Max-Age=1800", "HttpOnly", "SameSite=Strict"))
                .anySatisfy(cookie -> assertThat(cookie).contains("refreshToken=new-refresh", "Path=/v1/owner/auth/",
                        "Max-Age=1209600", "HttpOnly", "SameSite=Strict"));
    }

    @Test
    void RT_쿠키가_없으면_401_AUTH003이며_쿠키를_발급하지_않는다() throws Exception {
        when(service.refresh(null)).thenThrow(new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID));
        mvc.perform(post("/v1/owner/auth/tokens:refresh"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-003"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @ParameterizedTest
    @EnumSource(value = AuthErrorCode.class, names = {"REFRESH_TOKEN_INVALID", "REFRESH_TOKEN_REUSED", "REFRESH_TOKEN_STORE_UNAVAILABLE"})
    void 실패_응답에는_새_토큰_쿠키가_없다(AuthErrorCode error) throws Exception {
        when(service.refresh(anyString())).thenThrow(new AuthException(error));
        mvc.perform(post("/v1/owner/auth/tokens:refresh").cookie(new Cookie("refreshToken", "bad-refresh")))
                .andExpect(status().is(error.getHttpStatus().value()))
                .andExpect(jsonPath("$.code").value(error.getCode()))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
