package com.launchcatch.auth.opaque;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.AccessTokenValidAfterRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 역할별 DB 백업 포트와 Redis를 조합해 opaque Refresh Token의 발급·회전·폐기를 일관되게 처리한다. */
@Slf4j
@Service
public class OpaqueRefreshTokenLifecycle {

    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessTokenValidAfterRepository accessTokenValidAfterRepository;
    private final Map<Role, RefreshTokenBackupStore> backupStores;

    public OpaqueRefreshTokenLifecycle(
            RefreshTokenRepository refreshTokenRepository,
            AccessTokenValidAfterRepository accessTokenValidAfterRepository,
            List<RefreshTokenBackupStore> backupStores) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.accessTokenValidAfterRepository = accessTokenValidAfterRepository;
        Map<Role, RefreshTokenBackupStore> stores = new EnumMap<>(Role.class);
        backupStores.forEach(store -> stores.put(store.role(), store));
        this.backupStores = Map.copyOf(stores);
    }

    public void issue(Long subjectId, Role role, String refreshToken, Duration ttl, LocalDateTime now) {
        RefreshTokenBackupStore store = store(role);
        String hash = TokenHasher.sha256(refreshToken);
        try {
            if (!store.save(subjectId, hash, now.plus(ttl), now)) {
                throw unavailable();
            }
            try {
                refreshTokenRepository.save(refreshToken, subjectId, role, true, ttl);
            } catch (DataAccessException e) {
                log.warn("event=REFRESH_CACHE_SAVE_FAILED role={} subjectId={}", role, subjectId, e);
            }
        } catch (DataAccessException e) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
        }
    }

    public ReissueResult reissue(
            String oldRefreshToken,
            String newRefreshToken,
            Role role,
            Duration refreshTokenTtl,
            Duration accessTokenTtl,
            LocalDateTime now) {
        RefreshTokenRepository.RotateOutcome outcome;
        try {
            outcome = refreshTokenRepository.compareAndRotate(oldRefreshToken, newRefreshToken, refreshTokenTtl);
        } catch (DataAccessException redisFailure) {
            return reissueFromDatabase(oldRefreshToken, newRefreshToken, role, refreshTokenTtl, now);
        }
        if (outcome.isReuseDetected()) {
            revoke(outcome.data().role(), outcome.data().id(), now, accessTokenTtl);
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_REUSED);
        }
        if (!outcome.isSuccess()) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        String oldHash = TokenHasher.sha256(oldRefreshToken);
        String newHash = TokenHasher.sha256(newRefreshToken);
        if (outcome.data().role() != role) {
            compensate(newHash, outcome.data().role(), outcome.data().id());
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        try {
            if (!store(role).rotateIfMatches(
                    outcome.data().id(), oldHash, newHash, now.plus(refreshTokenTtl), now)) {
                compensate(newHash, role, outcome.data().id());
                throw unavailable();
            }
        } catch (DataAccessException dbFailure) {
            compensate(newHash, role, outcome.data().id());
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, dbFailure);
        }
        return new ReissueResult(outcome.data().id(), newRefreshToken);
    }

    public void revoke(Role role, Long subjectId, LocalDateTime now, Duration accessTokenTtl) {
        try {
            RefreshTokenBackupStore store = store(role);
            String cachedHash = refreshTokenRepository.findActiveHash(role, subjectId).orElse(null);
            String databaseHash = store.findCurrentHash(subjectId).orElse(null);
            if (databaseHash != null) {
                if (!store.clearIfMatches(subjectId, databaseHash, now)) {
                    throw unavailable();
                }
                refreshTokenRepository.revokeIfActiveHashMatches(databaseHash, role, subjectId);
                if (cachedHash != null && !cachedHash.equals(databaseHash)) {
                    refreshTokenRepository.deleteActiveKeyIfMatches(role, subjectId, cachedHash);
                }
            } else if (cachedHash != null) {
                refreshTokenRepository.revokeIfActiveHashMatches(cachedHash, role, subjectId);
            } else {
                refreshTokenRepository.deleteActiveKey(role, subjectId);
            }
            accessTokenValidAfterRepository.invalidateBefore(role, subjectId, now, accessTokenTtl);
        } catch (DataAccessException e) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
        }
    }

    private ReissueResult reissueFromDatabase(String oldToken, String newToken, Role role, Duration ttl, LocalDateTime now) {
        RefreshTokenBackup backup;
        try {
            String oldHash = TokenHasher.sha256(oldToken);
            backup = store(role).findValidByHash(oldHash, now)
                    .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID));
            if (backup.role() != role) {
                throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
            }
            String newHash = TokenHasher.sha256(newToken);
            if (!store(role).rotateIfMatches(backup.subjectId(), oldHash, newHash, now.plus(ttl), now)) {
                throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
            }
        } catch (DataAccessException dbFailure) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, dbFailure);
        }
        try {
            refreshTokenRepository.save(newToken, backup.subjectId(), role, true, ttl);
        } catch (DataAccessException e) {
            log.warn("event=REFRESH_CACHE_SAVE_AFTER_DB_FALLBACK_FAILED role={} subjectId={}", role, backup.subjectId(), e);
        }
        return new ReissueResult(backup.subjectId(), newToken);
    }

    private void compensate(String hash, Role role, Long subjectId) {
        try { refreshTokenRepository.revokeIfActiveHashMatches(hash, role, subjectId); }
        catch (DataAccessException e) { log.warn("event=REFRESH_CACHE_ROTATION_COMPENSATION_FAILED role={} subjectId={}", role, subjectId, e); }
    }

    private RefreshTokenBackupStore store(Role role) {
        RefreshTokenBackupStore store = backupStores.get(role);
        if (store == null) throw unavailable();
        return store;
    }

    private AuthException unavailable() { return new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE); }

    public record ReissueResult(Long subjectId, String refreshToken) { }
}
