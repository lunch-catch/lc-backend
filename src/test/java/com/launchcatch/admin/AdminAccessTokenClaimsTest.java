package com.launchcatch.admin;

import static org.assertj.core.api.Assertions.*;
import com.launchcatch.auth.exception.AuthException;
import java.util.Map;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdminAccessTokenClaimsTest {
    private final JwtTokenProvider jwt = new JwtTokenProvider(
            "test-only-secret-for-admin-access-version-0123456789abcdef",
            Duration.ofMinutes(30).toMillis(), Duration.ofDays(1).toMillis(),
            Duration.ofDays(14).toMillis(), Duration.ofDays(14).toMillis(), Clock.systemUTC());

    @Test
    void 승인된_발급_순번을_관리자_Access_Token에_담는다() {
        String token = jwt.createAccessToken(7L, Role.ADMIN, AdminAccessTokenClaims.forVersion(12L));

        assertThat(jwt.parseClaims(token).get("issuanceVersion", Long.class)).isEqualTo(12L);
    }


    @Test
    void 발급_순번을_왕복하고_BIGINT_정밀도를_유지한다() {
        assertThat(AdminAccessTokenClaims.issuanceVersion(AdminAccessTokenClaims.forVersion(Long.MAX_VALUE)))
                .isEqualTo(Long.MAX_VALUE);
        assertThat(AdminAccessTokenClaims.issuanceVersion(Map.of("issuanceVersion", 10))).isEqualTo(10L);
    }

    @Test
    void 기존_토큰의_순번_누락과_잘못된_자료형을_구분한다() {
        assertThat(AdminAccessTokenClaims.issuanceVersion(Map.of())).isNull();
        assertThatThrownBy(() -> AdminAccessTokenClaims.issuanceVersion(Map.of("issuanceVersion", "10")))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> AdminAccessTokenClaims.issuanceVersion(Map.of("issuanceVersion", 10.5)))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void 양수가_아닌_발급_순번을_JWT에_싣지_않는다() {
        assertThatThrownBy(() -> AdminAccessTokenClaims.forVersion(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminAccessTokenClaims.forVersion(-1L)).isInstanceOf(IllegalArgumentException.class);
    }
}
