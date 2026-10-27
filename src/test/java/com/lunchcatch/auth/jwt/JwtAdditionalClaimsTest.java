package com.lunchcatch.auth.jwt;

import static org.assertj.core.api.Assertions.*;
import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JwtAdditionalClaimsTest {
    private final JwtTokenProvider jwt = new JwtTokenProvider("test-only-secret-for-generic-claims-0123456789abcdef",
            1800000L, 86400000L, 1209600000L, 1209600000L, Clock.systemUTC());

    @Test
    void 도메인_추가_클레임을_의미해석없이_왕복한다() {
        var token = jwt.createAccessToken(7L, Role.OWNER, Map.of("deviceLabel", "counter"));
        assertThat(jwt.getAdditionalClaims(token)).containsExactlyEntriesOf(Map.of("deviceLabel", "counter"));
        assertThat(jwt.getId(token)).isEqualTo(7L);
        assertThat(jwt.getRole(token)).isEqualTo(Role.OWNER);
        assertThat(jwt.getIssuedAt(token)).isNotNull();
        assertThat(jwt.getAdditionalClaims(jwt.createAccessToken(7L, Role.OWNER))).isEmpty();
    }

    @Test
    void 도메인_클레임으로_주체_권한_만료를_덮어쓸_수_없다() {
        for (String reserved : new String[] {"sub", "role", "iat", "exp"}) {
            assertThatThrownBy(() -> jwt.createAccessToken(7L, Role.OWNER, Map.of(reserved, "override")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 인증_주체는_추가_클레임을_변경할_수_없는_복사본으로_보관한다() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("deviceLabel", "counter");
        var principal = new CustomUserDetails(7L, Role.OWNER, null, claims);
        claims.put("deviceLabel", "changed");
        assertThat(principal.getAdditionalClaims()).containsEntry("deviceLabel", "counter");
        assertThatThrownBy(() -> principal.getAdditionalClaims().put("deviceLabel", "changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
