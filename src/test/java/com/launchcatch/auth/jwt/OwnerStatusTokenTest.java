package com.launchcatch.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import com.launchcatch.auth.Role;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OwnerStatusTokenTest {
    @Test
    void 모든_역할의_토큰에_점주_상태를_저장하지_않는다() {
        var provider = new JwtTokenProvider(
                "test-only-secret-for-owner-login-0123456789abcdef",
                Duration.ofMinutes(30).toMillis(), Duration.ofDays(1).toMillis(),
                Duration.ofDays(14).toMillis(), Duration.ofDays(14).toMillis(), Clock.systemUTC());
        for (Role role : Role.values()) {
            String token = provider.createAccessToken(7L, role);
            assertThat(provider.parseClaims(token)).doesNotContainKey("ownerStatus");
            assertThat(provider.getRole(token)).isEqualTo(role);
            assertThat(provider.getId(token)).isEqualTo(7L);
            assertThat(provider.getIssuedAt(token)).isNotNull();
        }
    }
}
