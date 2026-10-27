package com.lunchcatch.admin.service;

import com.lunchcatch.admin.AdminAccessTokenClaims;
import com.lunchcatch.admin.repository.AdminAccessTokenVersionRepository;
import com.lunchcatch.admin.service.AdminAccessTokenVerifier;
import com.lunchcatch.auth.jwt.AccessTokenCutoffVerifier;
import com.lunchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.lunchcatch.auth.jwt.JwtAuthenticationFilter;
import com.lunchcatch.auth.jwt.JwtTokenProvider;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunchcatch.auth.Role;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class AdminAccessTokenVersionTest {
    private final com.lunchcatch.admin.repository.AdminAccessTokenRevocationRepository revocations = mock(com.lunchcatch.admin.repository.AdminAccessTokenRevocationRepository.class);
    private final Instant now = Instant.now();
    private final AdminAccessTokenVersionRepository repository = mock(AdminAccessTokenVersionRepository.class);

    private final AccessTokenValidAfterRepository legacy = mock(AccessTokenValidAfterRepository.class);

    private JwtTokenProvider provider(Instant instant) {
        return new JwtTokenProvider("test-only-secret-for-version-0123456789abcdef", 1800000,
                86400000, 1209600000, 1209600000, Clock.fixed(instant, ZoneOffset.UTC));
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    void 로그아웃_후_지연_생성된_JWT도_DB에서_승인받은_이전_순번으로_거부한다(Role role) throws Exception {
        // DB 승인 순번 10 이후 로그아웃이 11을 폐기 기준으로 게시한 순서를 재현한다.
        when(repository.isVersionValid(role, 1L, 10L)).thenReturn(false);
        var jwt = provider(now.plusSeconds(1));
        var token = jwt.createAccessToken(1L, role, AdminAccessTokenClaims.forVersion(10L));
        assertThat(AdminAccessTokenClaims.issuanceVersion(jwt.getAdditionalClaims(token))).isEqualTo(10L);
        assertAuthenticated(jwt, token, false);
        verify(repository).isVersionValid(role, 1L, 10L);
    }

    @org.junit.jupiter.api.BeforeEach
    void databaseAllowsUnrevokedTokens() {
        when(revocations.findRevocation(any())).thenReturn(java.util.Optional.of(new com.lunchcatch.admin.AdminAccessTokenRevocation(0L, null)));
    }

    @Test
    void 같은_초의_이전_토큰은_차단하고_재로그인_순번은_허용한다() throws Exception {
        var jwt = provider(now);
        var old = jwt.createAccessToken(1L, Role.ADMIN, AdminAccessTokenClaims.forVersion(10L));
        var fresh = jwt.createAccessToken(1L, Role.ADMIN, AdminAccessTokenClaims.forVersion(12L));
        when(repository.isVersionValid(Role.ADMIN, 1L, 10L)).thenReturn(false);
        when(repository.isVersionValid(Role.ADMIN, 1L, 12L)).thenReturn(true);
        assertThat(jwt.getIssuedAt(old)).isEqualTo(jwt.getIssuedAt(fresh));
        assertAuthenticated(jwt, old, false);
        assertAuthenticated(jwt, fresh, true);
    }

    @Test
    void 순번_없는_기존_관리자_JWT는_기존_시각_검증을_따른다() throws Exception {
        var jwt = provider(now);
        assertAuthenticated(jwt, jwt.createAccessToken(1L, Role.ADMIN), false);
        verifyNoInteractions(repository);
        verify(legacy).isValidAfter(org.mockito.ArgumentMatchers.eq(Role.ADMIN), org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void BIGINT_최댓값을_손실없이_JWT로_전달한다() {
        var jwt = provider(now);
        assertThat(AdminAccessTokenClaims.issuanceVersion(jwt.getAdditionalClaims(jwt.createAccessToken(1L, Role.ADMIN, AdminAccessTokenClaims.forVersion(Long.MAX_VALUE)))))
                .isEqualTo(Long.MAX_VALUE);
    }

    private void assertAuthenticated(JwtTokenProvider jwt, String token, boolean expected) throws Exception {
        SecurityContextHolder.clearContext();
        var request = new MockHttpServletRequest("GET", "/v1/admin/test");
        request.setCookies(new Cookie("adminAccessToken", token));
        new JwtAuthenticationFilter(jwt, legacy, java.util.List.of(new AdminAccessTokenVerifier(repository, new AccessTokenCutoffVerifier(legacy), revocations, org.mockito.Mockito.mock(com.lunchcatch.admin.service.AdminSessionService.class)))).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication() != null).isEqualTo(expected);
        });
    }
}
