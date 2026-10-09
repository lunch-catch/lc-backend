package com.launchcatch.auth.opaque;

import com.launchcatch.auth.RedisFailureClassifier;
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
        backupStores.forEach(store -> {
            RefreshTokenBackupStore previous = stores.put(store.role(), store);
            if (previous != null) {
                throw new IllegalStateException("duplicate RefreshTokenBackupStore role: " + store.role());
            }
        });
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
            return reissueFromDatabase(
                    oldRefreshToken, newRefreshToken, role, refreshTokenTtl, now, redisFailure);
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
            rollbackRotationOrThrow(
                    oldHash, newHash, outcome.data().role(), outcome.data().id());
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        try {
            if (!store(role).rotateIfMatches(
                    outcome.data().id(), oldHash, newHash, now.plus(refreshTokenTtl), now)) {
                if (!confirmOrRollbackDatabaseRotation(
                        outcome.data().id(), role, oldHash, newHash)) {
                    throw unavailable();
                }
            }
        } catch (DataAccessException dbFailure) {
            if (!confirmOrRollbackDatabaseRotation(
                    outcome.data().id(), role, oldHash, newHash)) {
                throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, dbFailure);
            }
        }
        return new ReissueResult(outcome.data().id(), newRefreshToken);
    }

    /*
     * Redis 로 회전하지 못할 때의 재발급이다. 관계형 DB 의 해시를 기준으로 판정한다.
     *
     * 캐시가 정상적으로 만료, 폐기, 재사용을 판정한 토큰은 여기로 오지 않는다. DB 에 같은 해시가
     * 남아 있고 만료 전이며 로그인 가능한 상태일 때만 회전시킨다. 회전은 이전 해시가 일치할 때만
     * 갱신하는 CAS 라서 같은 토큰으로 동시에 와도 하나만 성공한다.
     *
     * 이 경로는 재사용을 탐지하지 못한다. 폐기된 토큰의 흔적이 캐시에만 있기 때문이다.
     * 회전한 뒤에는 캐시가 살아 있으면 새 토큰을 채워 넣고 이전 토큰을 지운다. 실패해도 DB 가
     * 기준이므로 로그만 남긴다.
     */
    private ReissueResult reissueFromDatabase(
            String oldRefreshToken,
            String newRefreshToken,
            Role role,
            Duration refreshTokenTtl,
            LocalDateTime now,
            DataAccessException redisFailure) {
        log.warn("event=REFRESH_REISSUE_DB_FALLBACK role={} cause={}",
                role, RedisFailureClassifier.causeLabel(redisFailure), redisFailure);
        RefreshTokenBackupStore store = store(role);
        String oldHash = TokenHasher.sha256(oldRefreshToken);
        String newHash = TokenHasher.sha256(newRefreshToken);
        RefreshTokenBackup backup;
        try {
            backup = store.findValidByHash(oldHash, now)
                    .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID));
        } catch (DataAccessException dbFailure) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, dbFailure);
        }
        if (backup.role() != role) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        boolean rotated;
        try {
            rotated = store.rotateIfMatches(
                    backup.subjectId(), oldHash, newHash, now.plus(refreshTokenTtl), now);
        } catch (DataAccessException dbFailure) {
            confirmFallbackRotation(store, backup.subjectId(), newHash, dbFailure);
            rotated = true;
        }
        if (!rotated) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        syncCacheAfterFallback(backup.subjectId(), role, oldHash, newRefreshToken, refreshTokenTtl);
        return new ReissueResult(backup.subjectId(), newRefreshToken);
    }

    /* DB 회전이 예외로 끝났어도 새 해시가 이미 반영됐으면 성공이다. 아니면 저장소 장애로 실패한다. */
    private void confirmFallbackRotation(
            RefreshTokenBackupStore store, Long subjectId, String newHash, DataAccessException dbFailure) {
        try {
            if (store.findCurrentHash(subjectId).filter(newHash::equals).isPresent()) {
                return;
            }
        } catch (DataAccessException confirmationFailure) {
            dbFailure.addSuppressed(confirmationFailure);
        }
        throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, dbFailure);
    }

    private void syncCacheAfterFallback(
            Long subjectId, Role role, String oldHash, String newRefreshToken, Duration ttl) {
        try {
            refreshTokenRepository.save(newRefreshToken, subjectId, role, true, ttl);
            refreshTokenRepository.revokeIfActiveHashMatches(oldHash, role, subjectId);
        } catch (DataAccessException e) {
            log.warn("event=REFRESH_CACHE_SYNC_AFTER_DB_FALLBACK_FAILED role={} subjectId={}", role, subjectId, e);
        }
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

    private boolean confirmOrRollbackDatabaseRotation(
            Long subjectId, Role role, String oldHash, String newHash) {
        String currentHash;
        try {
            currentHash = store(role).findCurrentHash(subjectId).orElse(null);
        } catch (DataAccessException confirmationFailure) {
            compensate(newHash, role, subjectId);
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, confirmationFailure);
        }
        if (newHash.equals(currentHash)) {
            return true;
        }
        if (oldHash.equals(currentHash)) {
            rollbackRotationOrThrow(oldHash, newHash, role, subjectId);
            return false;
        }
        compensate(newHash, role, subjectId);
        return false;
    }

    private void rollbackRotationOrThrow(String oldHash, String newHash, Role role, Long subjectId) {
        try {
            if (!refreshTokenRepository.rollbackRotation(oldHash, newHash, role, subjectId)) {
                throw unavailable();
            }
        } catch (DataAccessException rollbackFailure) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, rollbackFailure);
        }
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
