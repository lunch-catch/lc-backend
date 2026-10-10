package com.launchcatch.auth.jwt;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.launchcatch.auth.Role;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AccessTokenPolicyTest {
    @ParameterizedTest
    @EnumSource(Role.class)
    void 역할별_추가_정보를_서명하고_해당_정책으로_검증한다(Role role) {
        var policy = mock(AccessTokenPolicy.class);
        when(policy.role()).thenReturn(role);
        when(policy.additionalClaims(7L)).thenReturn(Map.of("custom", "value"));
        var jwt = provider(List.of(policy));
        String token = jwt.createAccessToken(7L, role);
        assertThat(jwt.parseClaims(token)).containsEntry("custom", "value");
        when(policy.isValid(eq(7L), any())).thenReturn(false);
        assertThat(jwt.validateAdditionalClaims(token)).isFalse();
        when(policy.isValid(eq(7L), any())).thenReturn(true);
        assertThat(jwt.validateAdditionalClaims(token)).isTrue();
        assertThat(jwt.getRole(token)).isEqualTo(role);
    }

    @Test
    void 같은_역할의_정책을_중복_등록하지_못한다() {
        var policy = mock(AccessTokenPolicy.class);
        when(policy.role()).thenReturn(Role.OWNER);
        assertThatThrownBy(() -> provider(List.of(policy, policy))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 추가_정책이_없는_역할은_기존_JWT를_유지한다() {
        var jwt = provider(List.of());
        String token = jwt.createAccessToken(7L, Role.ADMIN);
        assertThat(jwt.parseClaims(token)).doesNotContainKey("custom");
        assertThat(jwt.validateAdditionalClaims(token)).isTrue();
    }

    private JwtTokenProvider provider(List<AccessTokenPolicy> policies) {
        return new JwtTokenProvider("test-secret-key-that-is-at-least-32-bytes-long-for-hmac", 1800000,
                86400000, 1209600000, 1209600000, Clock.system(ZoneId.of("Asia/Seoul")), policies);
    }
}
