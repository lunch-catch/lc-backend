package com.launchcatch.admin.service;

import com.launchcatch.admin.dto.AdminLoginResult;
import com.launchcatch.admin.repository.AdminSessionRefreshTokenRepository;
import com.launchcatch.admin.service.AdminSessionTransactionService.SessionState;
import com.launchcatch.auth.RedisFailureClassifier;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.OpaqueTokenGenerator;
import com.launchcatch.auth.opaque.RefreshTokenRepository.RefreshTokenData;
import com.launchcatch.auth.opaque.RefreshTokenRepository.RotateOutcome;
import com.launchcatch.auth.opaque.TokenHasher;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdminSessionService {
    private final AdminSessionTransactionService transactions;
    private final AdminSessionRefreshTokenRepository tokens;
    private final AdminLogoutTransactionService logoutAudit;
    private final AdminTokenTransactionService tokenAudit;
    private final JwtTokenProvider jwt;
    private final Clock clock;

    public AdminLoginResult issue(Long adminId, String verifiedPasswordHash) {
        SessionState state = stored(() -> transactions.issue(adminId, verifiedPasswordHash));
        publish(state);
        return state.result();
    }

    public Optional<AdminLoginResult> reissue(String oldToken) {
        String oldHash = TokenHasher.sha256(oldToken);
        Optional<RefreshTokenData> cached;
        try {
            cached = tokens.find(oldToken);
        } catch (DataAccessException failure) {
            safeLog("LOOKUP_FAILED", failure);
            return fallback(oldHash);
        }
        if (cached.isEmpty()) { return fallback(oldHash); }
        RefreshTokenData owner = cached.get();
        if (!owner.role().isAdmin()) { throw invalid(); }
        String newToken = OpaqueTokenGenerator.generate();
        Duration ttl = Duration.ofMillis(jwt.refreshTokenValidityMs(owner.role()));
        RotateOutcome outcome;
        try {
            outcome = tokens.compareAndRotate(oldToken, newToken, ttl);
        } catch (DataAccessException failure) {
            if ("TIMEOUT".equals(RedisFailureClassifier.causeLabel(failure))) {
                Optional<RefreshTokenData> confirmed;
                try {
                    confirmed = tokens.find(newToken);
                } catch (DataAccessException confirmationFailure) {
                    safeLog("TIMEOUT_CONFIRMATION_FAILED", confirmationFailure);
                    sessionFailureAudit(owner.id(), "ROTATION_RESULT_UNKNOWN");
                    throw unavailable();
                }
                if (confirmed.isEmpty() || !confirmed.get().equals(owner)) {
                    sessionFailureAudit(owner.id(), "ROTATION_RESULT_UNKNOWN");
                    throw unavailable();
                }
                return Optional.of(finish(owner, oldHash, newToken));
            }
            safeLog("ROTATION_FAILED", failure);
            return Optional.of(rotateAndPublish(owner, oldHash, newToken));
        }
        if (outcome.isReuseDetected()) {
            if (!owner.id().equals(outcome.data().id()) || owner.role() != outcome.data().role()) { throw invalid(); }
            var revoked = stored(() -> transactions.revokeReused(owner.id(), owner.role()));
            try {
                revokeCache(revoked);
            } finally { failureAudit(revoked.adminId(), "REUSE_DETECTED"); }
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_REUSED);
        }
        if (!outcome.isSuccess()) { throw invalid(); }
        if (!owner.id().equals(outcome.data().id()) || owner.role() != outcome.data().role()) {
            compensate(outcome.data(), TokenHasher.sha256(newToken));
            throw invalid();
        }
        return Optional.of(finish(owner, oldHash, newToken));
    }

    private Optional<AdminLoginResult> fallback(String oldHash) {
        var owner = stored(() -> transactions.findByHash(oldHash));
        if (owner.isEmpty()) { return Optional.empty(); }
        var data = new RefreshTokenData(owner.get().sessionId(), owner.get().role(), true);
        return Optional.of(rotateAndPublish(data, oldHash, OpaqueTokenGenerator.generate()));
    }

    private AdminLoginResult rotateAndPublish(RefreshTokenData owner, String oldHash, String newToken) {
        SessionState state;
        try {
            state = stored(() -> transactions.rotate(owner.id(), owner.role(), oldHash, newToken));
        } catch (RuntimeException failure) {
            sessionFailureAudit(owner.id(), "DB_FALLBACK_FAILED");
            throw failure;
        }
        publish(state);
        return state.result();
    }

    private AdminLoginResult finish(RefreshTokenData owner, String oldHash, String newToken) {
        SessionState state;
        try {
            state = stored(() -> transactions.rotate(owner.id(), owner.role(), oldHash, newToken));
        } catch (RuntimeException originalFailure) {
            state = recover(owner, oldHash, newToken, originalFailure);
        }
        publish(state);
        return state.result();
    }

    private SessionState recover(RefreshTokenData owner, String oldHash, String token, RuntimeException originalFailure) {
        boolean restored;
        try {
            var recovery = stored(() -> transactions.confirmRotation(owner.id(), owner.role(), oldHash, token));
            if (recovery.committed() != null) { return recovery.committed(); }
            restored = recovery.rolledBack()
                    && tokens.rollbackRotation(oldHash, TokenHasher.sha256(token), owner.role(), owner.id());
        } catch (RuntimeException recoveryFailure) {
            safeLog("RECOVERY_FAILED", recoveryFailure);
            compensate(owner, TokenHasher.sha256(token));
            sessionFailureAudit(owner.id(), "ROTATION_RESULT_UNKNOWN");
            throw unavailable();
        }
        if (!restored) { compensate(owner, TokenHasher.sha256(token)); }
        sessionFailureAudit(owner.id(), "DB_ROTATION_FAILED");
        throw originalFailure;
    }

    private void publish(SessionState state) {
        Duration remaining = Duration.between(LocalDateTime.now(clock), state.expiresAt());
        if (remaining.toMillis() <= 0) { return; }
        try {
            tokens.saveIfNewer(state.result().refreshToken(), state.sessionId(), state.result().response().role(),
                    remaining, state.version());
        } catch (DataAccessException failure) {
            // DB 백업을 기준으로 로그인·재발급 성공을 유지한다.
            safeLog("CACHE_PUBLICATION_FAILED", failure);
        }
    }

    public boolean logout(Long adminId, Role role, long version) {
        var revoked = stored(() -> transactions.logout(adminId, role, version));
        if (revoked.isEmpty()) { return false; }
        revokeCache(revoked.get());
        try {
            logoutAudit.recordSuccess(adminId);
        } catch (RuntimeException failure) {
            log.error("event=ADMIN_LOGOUT_AUDIT_FAILED adminId={} exceptionType={}",
                    adminId, failure.getClass().getSimpleName());
        }
        return true;
    }

    private void revokeCache(AdminSessionTransactionService.Revocation revoked) {
        stored(() -> {
            tokens.revokeBeforeVersion(revoked.sessionId(), revoked.role(), revoked.version());
            tokens.revokeIfActiveHashMatches(revoked.hash(), revoked.role(), revoked.sessionId());
            return true;
        });
    }

    private void compensate(RefreshTokenData owner, String hash) {
        stored(() -> {
            tokens.revokeIfActiveHashMatches(hash, owner.role(), owner.id());
            return true;
        });
    }

    public Optional<Boolean> isValid(Long adminId, Role role, long version) {
        return stored(() -> transactions.isValid(adminId, role, version));
    }

    private void failureAudit(Long adminId, String reason) {
        try {
            tokenAudit.recordFailure(adminId, reason);
        } catch (RuntimeException failure) {
            log.warn("event=ADMIN_TOKEN_REISSUE_AUDIT_FAILED adminId={} reason={} exceptionType={}",
                    adminId, reason, failure.getClass().getSimpleName());
        }
    }

    private void sessionFailureAudit(Long sessionId, String reason) {
        try {
            transactions.recordFailure(sessionId, reason);
        } catch (RuntimeException failure) {
            log.warn("event=ADMIN_TOKEN_REISSUE_AUDIT_FAILED sessionId={} reason={} exceptionType={}",
                    sessionId, reason, failure.getClass().getSimpleName());
        }
    }

    private <T> T stored(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException | TransactionException failure) {
            safeLog("STORE_FAILED", failure);
            throw unavailable();
        }
    }

    private void safeLog(String reason, RuntimeException failure) {
        log.warn("event=ADMIN_SESSION_FAILED reason={} exceptionType={}", reason, failure.getClass().getSimpleName());
    }
    private AuthException invalid() { return new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID); }
    private AuthException unavailable() { return new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE); }
}
