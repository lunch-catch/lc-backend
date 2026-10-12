package com.launchcatch.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.admin.dto.AdminLoginRequest;
import com.launchcatch.admin.dto.AdminLoginResponse;
import com.launchcatch.admin.dto.AdminLoginResult;
import com.launchcatch.admin.service.AdminTokenService;
import com.launchcatch.auth.AuthCookieFactory;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

class AdminTokenControllerTest {
    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    void 두_관리자_역할에_200_응답과_명세의_쿠키를_반환한다(Role role) {
        AdminTokenService service = mock(AdminTokenService.class);
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        when(jwt.getAccessTokenValidityMs()).thenReturn(Duration.ofMinutes(30).toMillis());
        when(jwt.refreshTokenValidityMs(role)).thenReturn(Duration.ofDays(1).toMillis());
        AuthCookieFactory cookies = new AuthCookieFactory(jwt);
        ReflectionTestUtils.setField(cookies, "secure", true);
        var request = new AdminLoginRequest("admin01", "Freshman!2026");
        var body = new AdminLoginResponse(1L, "관리자", role);
        when(service.reissue("a".repeat(43))).thenReturn(new AdminLoginResult(body, "access-raw", "refresh-raw"));
        var response = new AdminTokenController(service, cookies).reissue("a".repeat(43));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data()).isEqualTo(body);
        assertThat(response.getBody().code()).isEqualTo("SUCCESS");
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).hasSize(2)
                .anySatisfy(value -> assertThat(value).contains("accessToken=access-raw", "HttpOnly", "Secure",
                        "SameSite=Strict", "Path=/", "Max-Age=1800"))
                .anySatisfy(value -> assertThat(value).contains("refreshToken=refresh-raw", "HttpOnly", "Secure",
                        "SameSite=Strict", "Path=/v1/admin/auth/", "Max-Age=86400"));
    }
}
