package com.launchcatch.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
    void 양수가_아닌_발급_순번은_토큰에_담지_않는다() {
        assertThatIllegalArgumentException().isThrownBy(() -> AdminAccessTokenClaims.forVersion(0L));
    }
}
