package com.launchcatch.admin.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.launchcatch.admin.service.AdminLogoutService;
import com.launchcatch.auth.AuthCookieFactory;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

class AdminLogoutControllerTest {
    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    void 두_관리자_역할에_본문없는_204와_기존_Path의_삭제쿠키를_반환한다(Role role) {
        var service = mock(AdminLogoutService.class);
        var cookies = new AuthCookieFactory(mock(JwtTokenProvider.class));
        ReflectionTestUtils.setField(cookies, "secure", true);
        var response = new AdminLogoutController(service, cookies).logout(new CustomUserDetails(1L, role));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).hasSize(2)
                .anySatisfy(value -> assertThat(value).contains("accessToken=;", "Path=/;", "Max-Age=0", "Secure", "HttpOnly", "SameSite=Strict"))
                .anySatisfy(value -> assertThat(value).contains("refreshToken=;", "Path=/v1/admin/auth/", "Max-Age=0", "Secure", "HttpOnly", "SameSite=Strict"));
        verify(service).logout(1L, role);
    }
}
