package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.CutoffPolicy;
import com.launchcatch.owner.repository.OwnerAccessTokenVersionRepository;
import com.launchcatch.owner.repository.OwnerAccessTokenRevocationRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.CannotCreateTransactionException;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

class OwnerAccessTokenPolicyTest {
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 10, 13, 12, 0);
    private final OwnerAccessTokenVersionRepository versions = mock(OwnerAccessTokenVersionRepository.class);
    private final OwnerAccessTokenRevocationRepository revocations = mock(OwnerAccessTokenRevocationRepository.class);
    private final OwnerSessionService sessions = mock(OwnerSessionService.class);
    private final OwnerAccessTokenPolicy policy = new OwnerAccessTokenPolicy(sessions, versions, revocations);

    @BeforeEach
    void setUp() {
        when(revocations.findRevokedVersion(7L)).thenReturn(Optional.of(-1L));
    }

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
        when(versions.isValid(7L, 1L)).thenThrow(new DataAccessResourceFailureException("sensitive-token owner@example.com"));
        var claims = OwnerAccessTokenPolicy.additionalClaims(1L);
        try (var logs = LogCapture.attach(OwnerAccessTokenPolicy.class)) {
            assertThat(valid(claims)).isTrue();
            var event = logs.only();
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("event=OWNER_ACCESS_TOKEN_VERSION_CHECK_FAILED",
                    "ownerId=7", "policy=LENIENT", "exceptionType=DataAccessResourceFailureException", "fallback=ALLOW")
                    .doesNotContain("sensitive-token", "owner@example.com", "errorType=", "stack=");
            assertThat(event.getThrowableProxy()).isNull();
        }
        try (var logs = LogCapture.attach(OwnerAccessTokenPolicy.class)) {
            assertThatThrownBy(() -> policy.isValid(Role.OWNER, 7L, ISSUED_AT, claims, CutoffPolicy.REQUIRED))
                    .isInstanceOfSatisfying(AuthException.class,
                            e -> assertThat(e.getErrorCode().getCode()).isEqualTo("AUTH-002"));
            assertThat(logs.events()).isEmpty();
        }
    }

    @Test
    void DB에서_폐기한_AT는_Redis_상태와_무관하게_거부한다() {
        when(revocations.findRevokedVersion(7L)).thenReturn(Optional.of(2L));
        assertThat(valid(OwnerAccessTokenPolicy.additionalClaims(1L))).isFalse();
        assertThat(valid(OwnerAccessTokenPolicy.additionalClaims(2L))).isFalse();
        assertThat(valid(Map.of())).isFalse();
        verifyNoInteractions(versions);
    }

    @Test
    void 폐기_이후_새_순번의_AT는_허용한다() {
        when(revocations.findRevokedVersion(7L)).thenReturn(Optional.of(2L));
        when(versions.isValid(7L, 3L)).thenReturn(true);
        assertThat(valid(OwnerAccessTokenPolicy.additionalClaims(3L))).isTrue();
    }

    @Test
    void 없는_계정은_인증을_거부한다() {
        when(revocations.findRevokedVersion(7L)).thenReturn(Optional.empty());
        assertThat(valid(OwnerAccessTokenPolicy.additionalClaims(1L))).isFalse();
        verifyNoInteractions(versions);
    }

    @Test
    void DB_조회_장애는_LENIENT도_거부한다() {
        when(revocations.findRevokedVersion(7L)).thenThrow(new DataAccessResourceFailureException("sensitive-db-error owner@example.com"));
        assertDatabaseFailure("DataAccessResourceFailureException");
    }

    @Test
    void DB_트랜잭션_장애도_인증을_거부한다() {
        when(revocations.findRevokedVersion(7L)).thenThrow(new CannotCreateTransactionException("DB"));
        assertDatabaseFailure("CannotCreateTransactionException");
    }

    private void assertDatabaseFailure(String exceptionType) {
        var claims = OwnerAccessTokenPolicy.additionalClaims(1L);
        try (var logs = LogCapture.attach(OwnerAccessTokenPolicy.class)) {
            assertThat(valid(claims)).isFalse();
            assertThatThrownBy(() -> policy.isValid(Role.OWNER, 7L, ISSUED_AT, claims, CutoffPolicy.REQUIRED))
                    .isInstanceOfSatisfying(AuthException.class,
                            e -> assertThat(e.getErrorCode().getCode()).isEqualTo("AUTH-002"));
            assertThat(logs.events()).hasSize(2).allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("event=OWNER_ACCESS_TOKEN_DB_CHECK_FAILED", "ownerId=7",
                        "exceptionType=" + exceptionType).doesNotContain("sensitive-db-error", "owner@example.com", "errorType=", "stack=");
                assertThat(event.getThrowableProxy()).isNull();
            });
        }
        verifyNoInteractions(versions);
    }

    private boolean valid(Map<String, Object> claims) {
        return policy.isValid(Role.OWNER, 7L, ISSUED_AT, claims, CutoffPolicy.LENIENT);
    }

    @Test
    void 세션_AT는_해당_세션의_DB_검증을_따른다() {
        when(sessions.isValid(eq(7L), eq("key"), any())).thenReturn(true);
        assertThat(valid(Map.of(OwnerSessionService.SESSION_CLAIM, "key"))).isTrue();
        when(sessions.isValid(eq(7L), eq("key"), any())).thenReturn(false);
        assertThat(valid(Map.of(OwnerSessionService.SESSION_CLAIM, "key"))).isFalse();
        verifyNoInteractions(versions, revocations);
    }

    @Test
    void 세션_DB_조회_장애는_일반_인증도_통과시키지_않는다() {
        when(sessions.isValid(eq(7L), eq("key"), any())).thenThrow(new DataAccessResourceFailureException("DB"));
        var claims = Map.<String, Object>of(OwnerSessionService.SESSION_CLAIM, "key");
        assertThat(valid(claims)).isFalse();
        assertThatThrownBy(() -> policy.isValid(Role.OWNER, 7L, ISSUED_AT, claims, CutoffPolicy.REQUIRED))
                .isInstanceOfSatisfying(AuthException.class,
                        e -> assertThat(e.getErrorCode().getCode()).isEqualTo("AUTH-002"));
    }

}
