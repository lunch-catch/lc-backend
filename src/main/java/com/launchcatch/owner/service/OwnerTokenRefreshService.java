package com.launchcatch.owner.service;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.OpaqueTokenGenerator;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.TokenHasher;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.owner.dto.OwnerLoginResponse;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.repository.OwnerRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class OwnerTokenRefreshService {
    private final OwnerRepository owners;
    private final RefreshTokenRepository tokens;
    private final JwtTokenProvider jwt;
    private final AccessTokenValidAfterRepository cutoff;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public OwnerTokenRefreshService(OwnerRepository owners, RefreshTokenRepository tokens,
                                   JwtTokenProvider jwt, AccessTokenValidAfterRepository cutoff,
                                   Clock clock, PlatformTransactionManager transactionManager) {
        this.owners = owners;
        this.tokens = tokens;
        this.jwt = jwt;
        this.cutoff = cutoff;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        // DB 커밋 성공을 확인한 뒤 Redis에 게시하도록 DB 작업을 독립된 트랜잭션으로 처리한다.
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setTimeout(5);
    }

    public OwnerTokenRefreshResult refresh(String oldToken) {
        if (oldToken == null || oldToken.isBlank()) {
            throw invalid();
        }
        String oldHash = TokenHasher.sha256(oldToken);
        Candidate candidate = resolveCandidate(oldToken, oldHash);
        Long ownerId = candidate.ownerId();

        String newToken = OpaqueTokenGenerator.generate();
        Publication publication;
        try {
            publication = transactions.execute(transaction -> {
                Owner owner = owners.findByIdForLogin(ownerId).orElseThrow(this::invalid);
                LocalDateTime now = now();
                // 점주 행을 잠근 상태에서 RT를 검증하고 교체해, 동시 로그인·재발급이 서로 덮어쓰지 않도록 한다.
                if (!isValid(owner, oldHash, now)) {
                    throw invalid();
                }
                owner.rotateRefreshToken(TokenHasher.sha256(newToken),
                        now.plus(Duration.ofMillis(jwt.refreshTokenValidityMs(Role.OWNER))), now);
                owners.saveAndFlush(owner);
                Boolean tutorial = owner.getStatus() == OwnerStatus.ACTIVE ? owner.isTutorialViewed() : null;
                OwnerTokenRefreshResult result = new OwnerTokenRefreshResult(
                        new OwnerLoginResponse(owner.getEmail(), Role.OWNER, owner.getStatus(), tutorial),
                        jwt.createAccessToken(ownerId, Role.OWNER), newToken);
                return new Publication(result, owner.getRefreshTokenIssuanceVersion(), owner.getRefreshTokenExpiresAt());
            });
        } catch (DataAccessException | TransactionException e) {
            restoreAfterDatabaseFailure(ownerId, oldHash);
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
        }
        publish(ownerId, oldToken, publication, candidate.fallback());
        return publication.result();
    }

    /*
     * Redis에서 기존 RT를 한 번만 사용하도록 표시해 동시 재발급을 막는다.
     * Redis 장애일 때만 DB로 전환하며, 토큰 없음이나 재사용 판정을 DB로 우회하지 않는다.
     */
    private Candidate resolveCandidate(String oldToken, String oldHash) {
        RefreshTokenRepository.RotateOutcome outcome;
        try {
            outcome = tokens.consumeForRotation(oldToken, Role.OWNER);
        } catch (DataAccessException e) {
            logCacheFailure("OWNER_REFRESH_DB_FALLBACK", e);
            return new Candidate(findFallbackOwner(oldHash), true);
        }
        if (outcome.status() == RefreshTokenRepository.RotateOutcome.Status.NOT_FOUND
                || outcome.data().role() != Role.OWNER) {
            throw invalid();
        }
        Long ownerId = outcome.data().id();
        if (outcome.isReuseDetected()) {
            revokeReused(ownerId);
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_REUSED);
        }
        return new Candidate(ownerId, false);
    }

    private Long findFallbackOwner(String hash) {
        try {
            return owners.findByRefreshTokenHash(hash).map(Owner::getId).orElseThrow(this::invalid);
        } catch (DataAccessException | TransactionException e) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
        }
    }

    // DB에서 점주의 로그인 가능 상태, RT 해시 일치 여부, 만료 여부를 검증한다.
    private boolean isValid(Owner owner, String hash, LocalDateTime now) {
        return owner.canLogin() && hash.equals(owner.getRefreshTokenHash())
                && owner.getRefreshTokenExpiresAt() != null && owner.getRefreshTokenExpiresAt().isAfter(now);
    }

    /*
     * 재발급 실패 후 DB에서 기존 RT가 여전히 유효한지 확인하고, 유효하면 Redis에서도 다시 사용할 수 있게 한다.
     * 이미 교체·폐기됐거나 DB 확인에 실패한 토큰은 되살리지 않는다.
     */
    private void restoreAfterDatabaseFailure(Long ownerId, String oldHash) {
        try {
            transactions.executeWithoutResult(transaction -> owners.findByIdForLogin(ownerId)
                    .filter(owner -> isValid(owner, oldHash, now()))
                    .ifPresent(owner -> tokens.restoreConsumed(oldHash)));
        } catch (DataAccessException | TransactionException e) {
            logCacheFailure("OWNER_REFRESH_RESTORE_FAILED", e);
        }
    }

    // 커밋 이후 다른 로그인·재발급·폐기가 있었는지 DB를 다시 확인하고 현재 RT만 게시한다.
    private void publish(Long ownerId, String oldToken, Publication publication, boolean fallback) {
        boolean current;
        try {
            current = Boolean.TRUE.equals(transactions.execute(transaction -> {
                Owner owner = owners.findByIdForLogin(ownerId).orElseThrow(this::invalid);
                if (!owner.canLogin()) {
                    throw invalid();
                }
                return owner.getRefreshTokenIssuanceVersion() == publication.version()
                        && TokenHasher.sha256(publication.result().refreshToken()).equals(owner.getRefreshTokenHash());
            }));
        } catch (DataAccessException | TransactionException e) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
        }
        if (!current) {
            return;
        }
        Duration remaining = Duration.between(clock.instant(), publication.expiresAt().atZone(ClockConfig.ZONE).toInstant());
        if (remaining.toMillis() <= 0) {
            throw invalid();
        }
        try {
            if (fallback) {
                // DB에서 교체한 기존 RT를 Redis에서도 사용 불가로 표시하도록 재시도한다.
                tokens.consumeForRotation(oldToken, Role.OWNER);
            }
            // DB 잠금을 해제한 뒤 순번을 비교해, 늦게 도착한 이전 요청이 최신 RT를 덮어쓰지 못하게 한다.
            tokens.saveIfNewer(publication.result().refreshToken(), ownerId, Role.OWNER, true,
                    remaining, publication.version());
        } catch (DataAccessException e) {
            // DB 저장은 이미 완료됐으므로 Redis 게시 장애만으로 재발급 성공을 취소하지 않는다.
            logCacheFailure("OWNER_REFRESH_CACHE_PUBLISH_FAILED", e);
        }
    }

    /*
     * RT 재사용을 감지하면 DB의 현재 RT를 폐기하고 순번을 증가시킨 뒤 Redis에도 반영한다.
     * 이전 순번의 지연 게시를 차단하고, 폐기 기준 시각보다 먼저 발급된 AT도 거부하도록 설정한다.
     */
    private void revokeReused(Long ownerId) {
        LocalDateTime revokedAt = now();
        log.warn("event=OWNER_REFRESH_REUSE_DETECTED ownerId={}", ownerId);
        try {
            Revocation revocation = transactions.execute(transaction -> owners.findByIdForLogin(ownerId)
                    .map(owner -> {
                        String hash = owner.getRefreshTokenHash();
                        owner.clearRefreshToken();
                        owners.saveAndFlush(owner);
                        return new Revocation(hash, owner.getRefreshTokenIssuanceVersion());
                    }).orElse(null));
            if (revocation != null) {
                tokens.revokeThroughVersion(Role.OWNER, ownerId, revocation.hash(), revocation.version());
            }
            cutoff.invalidateBefore(Role.OWNER, ownerId, revokedAt, Duration.ofMillis(jwt.getAccessTokenValidityMs()));
        } catch (DataAccessException | TransactionException e) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
        }
    }

    private void logCacheFailure(String event, RuntimeException e) {
        // 예외 메시지에 토큰 값이 포함될 가능성을 피하고 예외 종류와 호출 위치만 남긴다.
        log.warn("event={} errorType={} stack={}", event, e.getClass().getSimpleName(), Arrays.toString(e.getStackTrace()));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ClockConfig.ZONE);
    }

    private AuthException invalid() {
        return new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
    }

    private record Candidate(Long ownerId, boolean fallback) { }
    private record Publication(OwnerTokenRefreshResult result, long version, LocalDateTime expiresAt) { }
    private record Revocation(String hash, long version) { }
}
