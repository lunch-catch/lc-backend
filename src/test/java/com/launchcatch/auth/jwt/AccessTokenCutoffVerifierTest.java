package com.launchcatch.auth.jwt;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;

class AccessTokenCutoffVerifierTest {
    private final AccessTokenValidAfterRepository repository = mock(AccessTokenValidAfterRepository.class);
    private final AccessTokenCutoffVerifier verifier = new AccessTokenCutoffVerifier(repository);
    private final LocalDateTime issuedAt = LocalDateTime.of(2026, 10, 8, 10, 0);

    @Test
    void 필수_조회_장애는_AUTH002로_변환한다() {
        var cause = new DataAccessResourceFailureException("Redis unavailable");
        when(repository.isValidAfter(Role.SUPER_ADMIN, 1L, issuedAt)).thenThrow(cause);
        assertThatThrownBy(() -> verifier.isValidAfter(Role.SUPER_ADMIN, 1L, issuedAt, CutoffPolicy.REQUIRED))
                .isInstanceOf(AuthException.class).hasCause(cause)
                .extracting(e -> ((AuthException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
    }

    @Test
    void 완화_정책은_조회_장애_시_허용한다() {
        when(repository.isValidAfter(Role.MEMBER, 1L, issuedAt))
                .thenThrow(new DataAccessResourceFailureException("Redis unavailable"));
        assertThat(verifier.isValidAfter(Role.MEMBER, 1L, issuedAt, CutoffPolicy.LENIENT)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(CutoffPolicy.class)
    void 발급_시각_누락은_정책과_무관하게_거부한다(CutoffPolicy policy) {
        assertThat(verifier.isValidAfter(Role.OWNER, 1L, null, policy)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(CutoffPolicy.class)
    void 폐기된_토큰은_거부한다(CutoffPolicy policy) {
        when(repository.isValidAfter(Role.SUPER_ADMIN, 1L, issuedAt)).thenReturn(false);
        assertThat(verifier.isValidAfter(Role.SUPER_ADMIN, 1L, issuedAt, policy)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(CutoffPolicy.class)
    void 정상_토큰은_허용한다(CutoffPolicy policy) {
        when(repository.isValidAfter(Role.SUPER_ADMIN, 1L, issuedAt)).thenReturn(true);
        assertThat(verifier.isValidAfter(Role.SUPER_ADMIN, 1L, issuedAt, policy)).isTrue();
    }
}
