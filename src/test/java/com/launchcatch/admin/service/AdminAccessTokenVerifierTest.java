package com.launchcatch.admin.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.launchcatch.admin.repository.AdminAccessTokenVersionRepository;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.AccessTokenCutoffVerifier;
import com.launchcatch.auth.jwt.CutoffPolicy;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;

class AdminAccessTokenVerifierTest {
    private final com.launchcatch.admin.repository.AdminAccessTokenRevocationRepository revocations = mock(com.launchcatch.admin.repository.AdminAccessTokenRevocationRepository.class);
    private final AdminAccessTokenVersionRepository versions = mock(AdminAccessTokenVersionRepository.class);
    private final AccessTokenCutoffVerifier legacy = mock(AccessTokenCutoffVerifier.class);
    private final AdminAccessTokenVerifier verifier = new AdminAccessTokenVerifier(versions, legacy, revocations, org.mockito.Mockito.mock(com.launchcatch.admin.service.AdminSessionService.class));
    private final LocalDateTime issuedAt = LocalDateTime.of(2026, 10, 12, 12, 0);

    @ParameterizedTest
    @EnumSource(Role.class)
    void 관리자_역할에만_정책을_적용한다(Role role) {
        assertThat(verifier.supports(role)).isEqualTo(role.isAdmin());
        assertThat(verifier.supports(null)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    void 새_JWT는_순번으로_검증하고_시각_커트라인은_조회하지_않는다(Role role) {
        when(versions.isVersionValid(role, 1L, 12L)).thenReturn(true);
        assertThat(verifier.isValidAfter(role, 1L, issuedAt, 12L, CutoffPolicy.REQUIRED)).isTrue();
        when(versions.isVersionValid(role, 1L, 10L)).thenReturn(false);
        assertThat(verifier.isValidAfter(role, 1L, issuedAt, 10L, CutoffPolicy.REQUIRED)).isFalse();
        verifyNoInteractions(legacy);
    }

    @ParameterizedTest
    @EnumSource(CutoffPolicy.class)
    void 기존_JWT의_시각_검증_결과를_유지한다(CutoffPolicy policy) {
        when(legacy.isValidAfter(Role.ADMIN, 1L, issuedAt, policy)).thenReturn(true);
        assertThat(verifier.isValidAfter(Role.ADMIN, 1L, issuedAt, null, policy)).isTrue();
        when(legacy.isValidAfter(Role.ADMIN, 1L, issuedAt, policy)).thenReturn(false);
        assertThat(verifier.isValidAfter(Role.ADMIN, 1L, issuedAt, null, policy)).isFalse();
        verifyNoInteractions(versions);
    }

    @org.junit.jupiter.api.BeforeEach
    void databaseAllowsUnrevokedTokens() {
        when(revocations.findRevocation(any())).thenReturn(java.util.Optional.of(new com.launchcatch.admin.AdminAccessTokenRevocation(0L, null)));
    }

    @Test
    void 일반_클레임에서_순번을_읽고_잘못된_자료형은_기존토큰으로_허용하지_않는다() {
        when(versions.isVersionValid(Role.ADMIN, 1L, 12L)).thenReturn(true);
        assertThat(verifier.isValid(Role.ADMIN, 1L, issuedAt,
                com.launchcatch.admin.AdminAccessTokenClaims.forVersion(12L), CutoffPolicy.REQUIRED)).isTrue();
        assertThat(verifier.isValid(Role.ADMIN, 1L, issuedAt,
                java.util.Map.of("issuanceVersion", "12"), CutoffPolicy.REQUIRED)).isFalse();
        verifyNoInteractions(legacy);
    }

    @Test
    void 필수_검증의_저장소_장애는_AUTH002다() {
        when(versions.isVersionValid(Role.ADMIN, 1L, 10L))
                .thenThrow(new DataAccessResourceFailureException("secret-must-not-leak"));
        assertThatThrownBy(() -> verifier.isValidAfter(Role.ADMIN, 1L, issuedAt, 10L, CutoffPolicy.REQUIRED))
                .isInstanceOfSatisfying(AuthException.class, failure -> {
                    assertThat(failure.getErrorCode()).isEqualTo(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
                    assertThat(failure.getCause()).isNull();
                });
    }

    @Test
    void 완화_검증의_저장소_장애는_기존_정책대로_허용한다() {
        when(versions.isVersionValid(Role.ADMIN, 1L, 10L))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThat(verifier.isValidAfter(Role.ADMIN, 1L, issuedAt, 10L, CutoffPolicy.LENIENT)).isTrue();
    }

    @Test
    void 잘못된_역할_시각_순번은_조회없이_거부한다() {
        assertThat(verifier.isValidAfter(Role.MEMBER, 1L, issuedAt, 10L, CutoffPolicy.REQUIRED)).isFalse();
        assertThat(verifier.isValidAfter(Role.ADMIN, 1L, null, 10L, CutoffPolicy.REQUIRED)).isFalse();
        assertThat(verifier.isValidAfter(Role.ADMIN, 1L, issuedAt, 0L, CutoffPolicy.REQUIRED)).isFalse();
        assertThat(verifier.isValidAfter(Role.ADMIN, 1L, issuedAt, -1L, CutoffPolicy.REQUIRED)).isFalse();
        verifyNoInteractions(versions, legacy);
    }
    @Test
    void Redis가_허용해도_DB에서_폐기된_새_JWT는_거부한다() {
        when(revocations.findRevocation(1L)).thenReturn(java.util.Optional.of(
                new com.launchcatch.admin.AdminAccessTokenRevocation(12L, issuedAt.plusSeconds(1))));
        assertThat(verifier.isValidAfter(Role.ADMIN, 1L, issuedAt.plusSeconds(2), 12L, CutoffPolicy.LENIENT)).isFalse();
        verifyNoInteractions(versions, legacy);
    }

    @Test
    void 기존_JWT도_DB_폐기_시각으로_거부한다() {
        when(revocations.findRevocation(1L)).thenReturn(java.util.Optional.of(
                new com.launchcatch.admin.AdminAccessTokenRevocation(12L, issuedAt.plusSeconds(1))));
        assertThat(verifier.isValidAfter(Role.ADMIN, 1L, issuedAt, null, CutoffPolicy.LENIENT)).isFalse();
        verifyNoInteractions(versions, legacy);
    }

    @ParameterizedTest
    @EnumSource(CutoffPolicy.class)
    void DB_계정이_없으면_거부한다(CutoffPolicy policy) {
        when(revocations.findRevocation(1L)).thenReturn(java.util.Optional.empty());
        assertThat(verifier.isValidAfter(Role.ADMIN, 1L, issuedAt, 12L, policy)).isFalse();
    }

    @Test
    void DB_장애는_완화정책에서도_인증하지_않는다() {
        when(revocations.findRevocation(1L)).thenThrow(new DataAccessResourceFailureException("secret"));
        assertThat(verifier.isValidAfter(Role.ADMIN, 1L, issuedAt, 12L, CutoffPolicy.LENIENT)).isFalse();
        verifyNoInteractions(versions, legacy);
    }

    @Test
    void DB_필수조회_트랜잭션_장애는_원인_없이_AUTH002다() {
        when(revocations.findRevocation(1L)).thenThrow(new org.springframework.transaction.CannotCreateTransactionException("secret"));
        assertThatThrownBy(() -> verifier.isValidAfter(Role.ADMIN, 1L, issuedAt, 12L, CutoffPolicy.REQUIRED))
                .isInstanceOfSatisfying(AuthException.class, failure -> {
                    assertThat(failure.getErrorCode()).isEqualTo(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
                    assertThat(failure.getCause()).isNull();
                });
    }

}
