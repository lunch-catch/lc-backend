package com.launchcatch.owner.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.launchcatch.owner.service.OwnerLoginResult;
import com.launchcatch.owner.service.OwnerLoginService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OwnerLoginControllerTest {
    private final OwnerLoginService service = mock(OwnerLoginService.class);
    private MockMvc mvc;
    private static final String BODY = "{\"email\":\"owner@example.com\",\"password\":\"password12\"}";

    @BeforeEach
    void setUp() {
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        when(jwt.getAccessTokenValidityMs()).thenReturn(Duration.ofMinutes(30).toMillis());
        when(jwt.refreshTokenValidityMs(Role.OWNER)).thenReturn(Duration.ofDays(14).toMillis());
        mvc = MockMvcBuilders.standaloneSetup(new OwnerLoginController(service, new AuthCookieFactory(jwt)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"ONBOARDING", "ACTIVE"})
    void 정상_응답과_쿠키_정책을_검증한다(OwnerStatus status) throws Exception {
        when(service.login(any())).thenReturn(new OwnerLoginResult(new OwnerLoginResponse(
                "owner@example.com", Role.OWNER, status, status == OwnerStatus.ACTIVE ? false : null),
                "test-access", "test-refresh"));
        var action = mvc.perform(post("/v1/owner/auth/tokens").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("owner@example.com"))
                .andExpect(jsonPath("$.data.role").value("OWNER"))
                .andExpect(jsonPath("$.data.status").value(status.name()));
        if (status == OwnerStatus.ACTIVE) {
            action.andExpect(jsonPath("$.data.tutorialViewed").value(false));
        } else {
            action.andExpect(jsonPath("$.data.tutorialViewed").doesNotExist());
        }
        var response = action.andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2).anySatisfy(cookie ->
                        assertThat(cookie).contains("accessToken=test-access", "Path=/;", "Max-Age=1800", "HttpOnly", "SameSite=Strict"))
                .anySatisfy(cookie -> assertThat(cookie).contains("refreshToken=test-refresh",
                        "Path=/v1/owner/auth/", "Max-Age=1209600", "HttpOnly", "SameSite=Strict"));
        assertThat(response.getContentAsString()).doesNotContain("test-access", "test-refresh", "password12");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"email\":\"bad\",\"password\":\"password12\"}",
            "{\"email\":\"owner@example.com\",\"password\":\"short\"}",
            "{\"email\":\"owner@example.com\",\"password\":\"          \"}"})
    void 입력_오류는_400이며_서비스를_호출하지_않는다(String body) throws Exception {
        mvc.perform(post("/v1/owner/auth/tokens").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @EnumSource(value = AuthErrorCode.class, names = {"LOGIN_FAILED", "REFRESH_TOKEN_STORE_UNAVAILABLE"})
    void 로그인_실패는_공통_오류이며_쿠키를_발급하지_않는다(AuthErrorCode code) throws Exception {
        when(service.login(any())).thenThrow(new AuthException(code));
        mvc.perform(post("/v1/owner/auth/tokens").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(code.getHttpStatus().value()))
                .andExpect(jsonPath("$.code").value(code.getCode()))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
}