package com.launchcatch.admin.service;

import com.launchcatch.admin.dto.AdminLoginResult;
import com.launchcatch.auth.RedisFailureClassifier;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.OpaqueTokenGenerator;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.RefreshTokenRepository.RefreshTokenData;
import com.launchcatch.auth.opaque.RefreshTokenRepository.RotateOutcome;
import com.launchcatch.auth.opaque.TokenHasher;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminTokenService {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private final RefreshTokenRepository tokens;
    private final AdminTokenTransactionService transactions;
    private final JwtTokenProvider jwt;
    private final Clock clock;

    @Transactional(propagation = Propagation.NEVER)
    public AdminLoginResult reissue(String oldToken) {
        if (oldToken == null || !TOKEN_PATTERN.matcher(oldToken).matches()) {
            throw invalid();
        }
        String newToken = OpaqueTokenGenerator.generate();
        String oldHash = TokenHasher.sha256(oldToken);
        String newHash = TokenHasher.sha256(newToken);
        Duration ttl = Duration.ofMillis(jwt.refreshTokenValidityMs(Role.ADMIN));
        LocalDateTime now = LocalDateTime.now(clock);
        Optional<RefreshTokenData> cached;
        try {
            cached = tokens.find(oldToken);
        } catch (DataAccessException failure) {
            safeLog("LOOKUP_FAILED", failure);
            return fallback(null, oldHash, newHash, newToken, ttl, now);
        }
        // DB 백업만 남은 로그인도 복구하되 정상적인 캐시 NOT_FOUND 회전 결과는 우회하지 않는다.
        if (cached.isEmpty()) {
            return fallback(null, oldHash, newHash, newToken, ttl, now);
        }
        RefreshTokenData owner = cached.get();
        if (!owner.role().isAdmin()) {
            throw invalid();
        }
        RotateOutcome outcome;
        try {
            outcome = tokens.compareAndRotate(oldToken, newToken, ttl);
        } catch (DataAccessException failure) {
            if ("TIMEOUT".equals(RedisFailureClassifier.causeLabel(failure))) {
                return confirmTimeout(owner, oldHash, newHash, newToken, ttl, now);
            }
            safeLog("ROTATION_FAILED", failure);
            return fallback(owner.id(), oldHash, newHash, newToken, ttl, now);
        }
        if (outcome.isReuseDetected()) {
            if (!outcome.data().role().isAdmin() || !owner.id().equals(outcome.data().id())) {
                throw invalid();
            }
            revokeReused(outcome.data());
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_REUSED);
        }
        if (!outcome.isSuccess()) {
            throw invalid();
        }
        RefreshTokenData rotated = outcome.data();
        if (!rotated.role().isAdmin() || !owner.id().equals(rotated.id())) {
            compensate(rotated, newHash);
            throw invalid();
        }
        return finish(rotated, oldHash, newHash, newToken, ttl, now);
    }

    private AdminLoginResult confirmTimeout(RefreshTokenData owner, String oldHash, String newHash,
            String newToken, Duration ttl, LocalDateTime now) {
        Optional<RefreshTokenData> confirmed;
        try {
            confirmed = tokens.find(newToken);
        } catch (DataAccessException failure) {
            safeLog("TIMEOUT_CONFIRMATION_FAILED", failure);
            failureAudit(owner.id(), "ROTATION_RESULT_UNKNOWN");
            throw unavailable();
        }
        if (confirmed.isEmpty() || !confirmed.get().equals(owner)) {
            failureAudit(owner.id(), "ROTATION_RESULT_UNKNOWN");
            throw unavailable();
        }
        return finish(owner, oldHash, newHash, newToken, ttl, now);
    }

    private AdminLoginResult finish(RefreshTokenData owner, String oldHash, String newHash,
            String newToken, Duration ttl, LocalDateTime now) {
        AdminTokenTransactionService.RotationState state;
        try {
            state = rotate(owner.id(), oldHash, newHash, ttl, now);
        } catch (RuntimeException failure) {
            compensate(owner, newHash);
            failureAudit(owner.id(), "DB_ROTATION_FAILED");
            throw failure;
        }
        publish(state, newToken, ttl);
        return result(state, newToken);
    }

    private AdminLoginResult fallback(Long id, String oldHash, String newHash,
            String newToken, Duration ttl, LocalDateTime now) {
        AdminTokenTransactionService.RotationState state;
        try {
            state = rotate(id, oldHash, newHash, ttl, now);
        } catch (RuntimeException failure) {
            failureAudit(id, "DB_FALLBACK_FAILED");
            throw failure;
        }
        publish(state, newToken, ttl);
        return result(state, newToken);
    }

    private AdminTokenTransactionService.RotationState rotate(Long id, String oldHash, String newHash,
            Duration ttl, LocalDateTime now) {
        try {
            return transactions.rotate(id, oldHash, newHash, now.plus(ttl), now);
        } catch (DataAccessException | TransactionException failure) {
            safeLog("DB_FAILED", failure);
            throw unavailable();
        }
    }

    private void publish(AdminTokenTransactionService.RotationState state, String token, Duration ttl) {
        try {
            tokens.saveIfNewer(token, state.response().adminId(), state.response().role(), true, ttl, state.version());
        } catch (DataAccessException failure) {
            safeLog("CACHE_PUBLICATION_FAILED", failure);
        }
    }

    private void revokeReused(RefreshTokenData owner) {
        try {
            AdminTokenTransactionService.RevocationState state = transactions.revokeReused(owner.id());
            // 역할이 변경됐어도 관리자 두 역할의 지연 게시를 모두 차단한다.
            tokens.revokeBeforeVersion(owner.id(), Role.ADMIN, state.version());
            tokens.revokeBeforeVersion(owner.id(), Role.SUPER_ADMIN, state.version());
            if (state.hash() != null) {
                tokens.revokeIfActiveHashMatches(state.hash(), state.role(), owner.id());
            }
        } catch (DataAccessException | TransactionException failure) {
            safeLog("REUSE_REVOCATION_FAILED", failure);
            throw unavailable();
        }
    }

    private void compensate(RefreshTokenData owner, String hash) {
        try {
            tokens.revokeIfActiveHashMatches(hash, owner.role(), owner.id());
        } catch (DataAccessException failure) {
            safeLog("COMPENSATION_FAILED", failure);
            failureAudit(owner.id(), "COMPENSATION_FAILED");
            throw unavailable();
        }
    }

    private void failureAudit(Long id, String reason) {
        if (id == null) {
            return;
        }
        try {
            transactions.recordFailure(id, reason);
        } catch (RuntimeException failure) {
            safeLog("FAILURE_AUDIT_FAILED", failure);
        }
    }

    private AdminLoginResult result(AdminTokenTransactionService.RotationState state, String token) {
        return new AdminLoginResult(state.response(),
                jwt.createAccessToken(state.response().adminId(), state.response().role()), token);
    }

    // 외부 예외 메시지와 cause에 토큰이 포함될 수 있어 종류와 스택 프레임만 기록한다.
    private void safeLog(String reason, RuntimeException failure) {
        log.warn("event=ADMIN_TOKEN_REISSUE_FAILURE reason={} errorType={} stack={}", reason,
                failure.getClass().getSimpleName(), java.util.Arrays.toString(failure.getStackTrace()));
    }

    private AuthException invalid() { return new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID); }
    private AuthException unavailable() { return new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE); }
}
