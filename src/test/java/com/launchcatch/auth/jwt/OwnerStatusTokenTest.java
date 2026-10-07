package com.launchcatch.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.servlet.http.Cookie;

class OwnerStatusTokenTest {
    private final JwtTokenProvider provider = new JwtTokenProvider(
            "test-only-secret-for-owner-login-0123456789abcdef",
            Duration.ofMinutes(30).toMillis(), Duration.ofDays(1).toMillis(),
            Duration.ofDays(14).toMillis(), Duration.ofDays(14).toMillis(), Clock.systemUTC());

    @Test
    void 점주_상태는_서명된_토큰에서_인증_주체로_전달된다() throws Exception {
        for (String status : new String[]{"ONBOARDING", "ACTIVE"}) {
            String token = provider.createAccessToken(7L, Role.OWNER, status);
            assertThat(provider.getOwnerStatus(token)).isEqualTo(status);
            assertThat(provider.getRole(token)).isEqualTo(Role.OWNER);
            assertThat(provider.getId(token)).isEqualTo(7L);
            var cutoff = mock(AccessTokenValidAfterRepository.class);
            when(cutoff.isValidAfter(any(), any(), any())).thenReturn(true);
            var request = new MockHttpServletRequest();
            request.setCookies(new Cookie("accessToken", token));
            try {
                new JwtAuthenticationFilter(provider, cutoff).doFilter(request, new MockHttpServletResponse(),
                        (req, res) -> {});
                var principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
                assertThat(principal.getOwnerStatus()).isEqualTo(status);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    @Test
    void 기존_역할의_발급_호출은_유지되며_점주_상태를_싣지_않는다() {
        for (Role role : new Role[]{Role.ADMIN, Role.SUPER_ADMIN, Role.MEMBER}) {
            String token = provider.createAccessToken(7L, role);
            assertThat(provider.getRole(token)).isEqualTo(role);
            assertThat(provider.getOwnerStatus(token)).isNull();
        }
        assertThat(new CustomUserDetails(7L, Role.ADMIN).getOwnerStatus()).isNull();
    }
}