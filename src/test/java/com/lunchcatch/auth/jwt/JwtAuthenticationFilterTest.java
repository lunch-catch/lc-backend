package com.lunchcatch.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

    private static final String TOKEN = "test-token";
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 10, 7, 12, 0);

    private final JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
    private final AccessTokenValidAfterRepository cutoffRepository = mock(AccessTokenValidAfterRepository.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/admin/admins");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        request.setCookies(new Cookie("adminAccessToken", TOKEN));
        when(tokenProvider.validateToken(TOKEN)).thenReturn(true);
        when(tokenProvider.getId(TOKEN)).thenReturn(1L);
        when(tokenProvider.getRole(TOKEN)).thenReturn(Role.SUPER_ADMIN);
        when(tokenProvider.getIssuedAt(TOKEN)).thenReturn(ISSUED_AT);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 기존_체인은_조회_장애_시_인증을_허용한다() throws Exception {
        when(cutoffRepository.isValidAfter(Role.SUPER_ADMIN, 1L, ISSUED_AT))
                .thenThrow(new DataAccessResourceFailureException("Redis unavailable"));

        assertAuthentication(new JwtAuthenticationFilter(tokenProvider, cutoffRepository), true);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void 모든_역할에서_발급_시각_누락을_거부한다(Role role) throws Exception {
        when(tokenProvider.getRole(TOKEN)).thenReturn(role);
        when(tokenProvider.getIssuedAt(TOKEN)).thenReturn(null);

        assertAuthentication(new JwtAuthenticationFilter(tokenProvider, cutoffRepository), false);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void 폐기_확인을_통과한_토큰은_인증한다(Role role) throws Exception {
        when(tokenProvider.getRole(TOKEN)).thenReturn(role);
        when(cutoffRepository.isValidAfter(role, 1L, ISSUED_AT)).thenReturn(true);

        assertAuthentication(new JwtAuthenticationFilter(tokenProvider, cutoffRepository), true);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void 사용_금지된_토큰은_인증하지_않는다(Role role) throws Exception {
        when(tokenProvider.getRole(TOKEN)).thenReturn(role);
        when(cutoffRepository.isValidAfter(role, 1L, ISSUED_AT)).thenReturn(false);

        assertAuthentication(new JwtAuthenticationFilter(tokenProvider, cutoffRepository), false);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void 서명이나_만료_검증에_실패하면_인증하지_않는다(Role role) throws Exception {
        when(tokenProvider.validateToken(TOKEN)).thenReturn(false);

        assertAuthentication(new JwtAuthenticationFilter(tokenProvider, cutoffRepository), false);
    }

    @Test
    void 쿠키가_없으면_인증하지_않는다() throws Exception {
        request.setCookies((Cookie[]) null);

        assertAuthentication(new JwtAuthenticationFilter(tokenProvider, cutoffRepository), false);
    }

    @Test
    void 역할이_없으면_인증하지_않는다() throws Exception {
        when(tokenProvider.getRole(TOKEN)).thenReturn(null);

        assertAuthentication(new JwtAuthenticationFilter(tokenProvider, cutoffRepository), false);
    }

    /*
     * 세 역할의 쿠키가 한 브라우저에 함께 있어도 요청 경로의 역할 쿠키만 읽는다.
     * 나중에 로그인한 역할의 토큰으로 다른 역할의 API 를 부르지 않게 한다(#841).
     */
    @ParameterizedTest
    @CsvSource({
            "/v1/admin/admins, adminAccessToken",
            "/v1/admin, adminAccessToken",
            "/v1/owner/campaigns, ownerAccessToken",
            "/v1/owner, ownerAccessToken",
            "/v1/feed, accessToken",
            "/v1/administrators, accessToken"
    })
    void 요청_경로의_역할_쿠키만_읽는다(String path, String cookieName) throws Exception {
        request.setRequestURI(path);
        request.setCookies(Stream.of("adminAccessToken", "ownerAccessToken", "accessToken")
                .map(name -> new Cookie(name, name.equals(cookieName) ? TOKEN : "other-role-token"))
                .toArray(Cookie[]::new));
        when(cutoffRepository.isValidAfter(Role.SUPER_ADMIN, 1L, ISSUED_AT)).thenReturn(true);

        assertAuthentication(new JwtAuthenticationFilter(tokenProvider, cutoffRepository), true);
    }

    // 관리자 경로에 회원 쿠키만 있으면 읽지 않는다. 배포 전의 accessToken 으로 되돌아가 읽지 않는다(#841)
    @Test
    void 관리자_경로에_회원_쿠키만_있으면_인증하지_않는다() throws Exception {
        request.setCookies(new Cookie("accessToken", TOKEN));

        assertAuthentication(new JwtAuthenticationFilter(tokenProvider, cutoffRepository), false);
    }

    private void assertAuthentication(JwtAuthenticationFilter filter, boolean expected) throws Exception {
        boolean[] continued = {false};
        filter.doFilter(request, response, (req, res) -> {
            continued[0] = true;
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (expected) {
                assertThat(authentication).isNotNull();
                assertThat(authentication.isAuthenticated()).isTrue();
                CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();
                assertThat(principal.getId()).isEqualTo(1L);
                assertThat(principal.getRole()).isEqualTo(tokenProvider.getRole(TOKEN));
                assertThat(principal.getIssuedAt()).isEqualTo(ISSUED_AT);
            } else {
                assertThat(authentication).isNull();
            }
        });
        assertThat(continued[0]).isTrue();
    }
}
