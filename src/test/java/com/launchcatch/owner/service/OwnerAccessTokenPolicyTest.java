package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.CutoffPolicy;
import com.launchcatch.owner.repository.OwnerAccessTokenVersionRepository;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

class OwnerAccessTokenPolicyTest {
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 10, 13, 12, 0);
    private final OwnerAccessTokenVersionRepository versions = mock(OwnerAccessTokenVersionRepository.class);
    private final OwnerAccessTokenPolicy policy = new OwnerAccessTokenPolicy(versions);

    @Test
    void 큰_순번도_문자열로_서명하고_검증한다() {
        var claims = OwnerAccessTokenPolicy.additionalClaims(Long.MAX_VALUE);
        assertThat(claims).containsEntry("issuanceVersion", Long.toString(Long.MAX_VALUE));
        when(versions.isValid(7L, Long.MAX_VALUE)).thenReturn(true);
        assertThat(valid(claims)).isTrue();
    }

    @Test
    void 음수_순번은_발급하지_않는다() {
        assertThatThrownBy(() -> OwnerAccessTokenPolicy.additionalClaims(-1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 점주_역할만_지원하고_인증_정보가_없으면_거부한다() {
        for (Role role : Role.values()) {
            assertThat(policy.supports(role)).isEqualTo(role == Role.OWNER);
        }
        assertThat(policy.supports(null)).isFalse();
        assertThat(policy.isValid(Role.ADMIN, 7L, ISSUED_AT, Map.of(), CutoffPolicy.LENIENT)).isFalse();
        assertThat(policy.isValid(Role.OWNER, null, ISSUED_AT, Map.of(), CutoffPolicy.LENIENT)).isFalse();
        assertThat(policy.isValid(Role.OWNER, 7L, null, Map.of(), CutoffPolicy.LENIENT)).isFalse();
        verifyNoInteractions(versions);
    }

    @Test
    void 기존_JWT는_0으로_검증하고_차단_결과를_따른다() {
        when(versions.isValid(7L, 0L)).thenReturn(true);
        assertThat(valid(Map.of())).isTrue();
        when(versions.isValid(7L, 0L)).thenReturn(false);
        assertThat(valid(Map.of())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "bad", "9223372036854775808", "12345678901234567890"})
    void 잘못된_순번은_거부한다(String version) {
        assertThat(valid(Map.of("issuanceVersion", version))).isFalse();
        verifyNoInteractions(versions);
    }

    @Test
    void 문자열이_아닌_순번은_거부한다() {
        assertThat(valid(Map.of("issuanceVersion", 1))).isFalse();
    }

    @Test
    void Redis_조회_장애는_LENIENT에서_허용하고_REQUIRED에서_거부한다() {
        when(versions.isValid(7L, 1L)).thenThrow(new DataAccessResourceFailureException("Redis"));
        var claims = OwnerAccessTokenPolicy.additionalClaims(1L);
        assertThat(valid(claims)).isTrue();
        assertThatThrownBy(() -> policy.isValid(Role.OWNER, 7L, ISSUED_AT, claims, CutoffPolicy.REQUIRED))
                .isInstanceOfSatisfying(AuthException.class,
                        e -> assertThat(e.getErrorCode().getCode()).isEqualTo("AUTH-002"));
    }

    private boolean valid(Map<String, Object> claims) {
        return policy.isValid(Role.OWNER, 7L, ISSUED_AT, claims, CutoffPolicy.LENIENT);
    }
}
