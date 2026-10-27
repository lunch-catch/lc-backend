package com.lunchcatch.owner.service;

import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.auth.jwt.JwtTokenProvider;
import com.lunchcatch.auth.jwt.CutoffPolicy;
import com.lunchcatch.auth.opaque.OpaqueTokenGenerator;
import com.lunchcatch.auth.opaque.TokenHasher;
import com.lunchcatch.global.config.ClockConfig;
import com.lunchcatch.owner.dto.OwnerLoginResponse;
import com.lunchcatch.owner.entity.Owner;
import com.lunchcatch.owner.entity.OwnerSession;
import com.lunchcatch.owner.entity.OwnerStatus;
import com.lunchcatch.owner.repository.OwnerRepository;
import com.lunchcatch.owner.repository.OwnerSessionRepository;
import com.lunchcatch.owner.repository.OwnerSessionTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class OwnerSessionService {
    public static final String SESSION_CLAIM = "ownerSession";
    private static final Duration ROTATION_GRACE = Duration.ofSeconds(10);
    private final OwnerRepository owners;
    private final OwnerSessionRepository sessions;
    private final OwnerSessionTokenRepository tokens;
    private final JwtTokenProvider jwt;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public OwnerSessionService(OwnerRepository owners, OwnerSessionRepository sessions, JwtTokenProvider jwt,
                               Clock clock, PlatformTransactionManager manager, OwnerSessionTokenRepository tokens) {
        this.tokens = tokens;
        this.owners = owners;
        this.sessions = sessions;
        this.jwt = jwt;
        this.clock = clock;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(5);
    }

    // 로그인 서비스가 점주 행을 잠근 트랜잭션 안에서 호출한다.
    @Transactional(propagation = Propagation.MANDATORY)
    public OwnerLoginResult issue(Owner owner) {
        LocalDateTime now = now();
        sessions.deleteExpired(owner.getId(), now);
        String token = OpaqueTokenGenerator.generate();
        OwnerSession session = sessions.saveAndFlush(OwnerSession.create(owner.getId(), owner.getSessionGeneration(),
                TokenHasher.sha256(token), now.plus(Duration.ofMillis(jwt.refreshTokenValidityMs(Role.OWNER))), now));
        owner.recordSessionLogin(now);
        return new OwnerLoginResult(response(owner), accessToken(owner.getId(), session), token);
    }

    // 새 세션에 없는 토큰만 이전 단일 토큰 구현으로 넘겨 기존 로그인을 유지한다.
    public Optional<OwnerTokenRefreshResult> refreshIfPresent(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            String hash = TokenHasher.sha256(token);
            Optional<OwnerSession> candidate = resolve(token, hash);
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            Rotation rotation;
            try {
                rotation = transactions.execute(tx -> {
                    OwnerSession found = candidate.orElseThrow();
                    Owner owner = owners.findByIdForLogin(found.getOwnerId()).orElseThrow(this::invalid);
                    OwnerSession session = sessions.findCurrent(found.getSessionKey(), owner.getId()).orElseThrow(this::invalid);
                    LocalDateTime now = now();
                    if (!owner.canLogin() || !session.isActive(owner.getSessionGeneration(), now)) {
                        throw invalid();
                    }
                    boolean current = hash.equals(session.getRefreshTokenHash());
                    boolean previous = hash.equals(session.getPreviousTokenHash());
                    if (!current && !previous) {
                        throw invalid();
                    }
                    if (previous && !session.isWithinGrace(now)) {
                        session.revoke(now);
                        sessions.saveAndFlush(session);
                        return new Rotation(null, true, session);
                    }
                    String replacement = null;
                    // 유예 중에는 회전을 다시 하지 않아 다른 탭의 응답이 최신 쿠키를 덮어쓰지 않게 한다.
                    if (!session.isWithinGrace(now)) {
                        replacement = OpaqueTokenGenerator.generate();
                        session.rotate(TokenHasher.sha256(replacement),
                                now.plus(Duration.ofMillis(jwt.refreshTokenValidityMs(Role.OWNER))), now, now.plus(ROTATION_GRACE));
                        sessions.saveAndFlush(session);
                    }
                    return new Rotation(new OwnerTokenRefreshResult(response(owner), accessToken(owner.getId(), session), replacement), false, session);
                });
            } catch (DataAccessException | TransactionException e) {
                restoreAfterFailure(candidate.orElseThrow(), hash);
                throw e;
            }
            if (rotation.reused()) {
                revokeCache(rotation.session());
                log.warn("event=OWNER_SESSION_REFRESH_REUSED ownerId={}", candidate.orElseThrow().getOwnerId());
                throw new AuthException(AuthErrorCode.REFRESH_TOKEN_REUSED);
            }
            if (rotation.result().refreshToken() != null) {
                publish(rotation.session(), rotation.result().refreshToken());
                try {
                    tokens.confirm(hash);
                } catch (DataAccessException e) {
                    log.warn("event=OWNER_SESSION_REFRESH_CONFIRM_FAILED ownerId={} exceptionType={} fallback=DB_HASH_CHECK",
                            rotation.session().getOwnerId(), e.getClass().getSimpleName());
                }
            } else if (hash.equals(rotation.session().getRefreshTokenHash())) {
                // 유예 중 현재 RT는 교체하지 않았으므로 처리 중 표시만 복구한다.
                restoreAfterFailure(rotation.session(), hash);
            }
            return Optional.of(rotation.result());
        } catch (DataAccessException | TransactionException e) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
        }
    }

    public void logout(Long ownerId, String key) {
        try {
            OwnerSession revoked = transactions.execute(tx -> {
                owners.findByIdForLogin(ownerId).orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
                OwnerSession session = sessions.findCurrent(key, ownerId)
                        .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
                session.revoke(now());
                sessions.saveAndFlush(session);
                return session;
            });
            revokeCache(revoked);
        } catch (DataAccessException | TransactionException e) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
        }
    }

    public boolean isValid(Long ownerId, Object key, CutoffPolicy policy) {
        if (!(key instanceof String text) || !text.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            return false;
        }
        // 별도 트랜잭션에서 커밋된 점주 상태와 세션을 함께 읽는다.
        boolean valid = Boolean.TRUE.equals(transactions.execute(tx -> {
            Owner owner = owners.findById(ownerId).orElse(null);
            // 업무별 상태 제한은 기존 점주 서비스가 판정하며 내 정보 조회·로그아웃은 유지한다.
            return owner != null && sessions.findCurrent(text, ownerId)
                    .filter(session -> session.isActive(owner.getSessionGeneration(), now())).isPresent();
        }));
        if (!valid) {
            return false;
        }
        try {
            return !tokens.isRevoked(text);
        } catch (DataAccessException e) {
            if (policy == CutoffPolicy.REQUIRED) {
                throw e;
            }
            log.warn("event=OWNER_SESSION_ACCESS_CACHE_FAILED ownerId={} exceptionType={} fallback=ALLOW_DB_VALIDATED",
                    ownerId, e.getClass().getSimpleName());
            return true;
        }
    }

    public void publishIssued(OwnerLoginResult result) {
        try {
            OwnerSession session = sessions.findByTokenHash(TokenHasher.sha256(result.refreshToken())).orElseThrow(this::invalid);
            publish(session, result.refreshToken());
        } catch (DataAccessException | TransactionException e) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
        }
    }

    private Optional<OwnerSession> resolve(String token, String hash) {
        Optional<OwnerSessionTokenRepository.TokenData> cached;
        try {
            cached = tokens.consume(token);
        } catch (DataAccessException e) {
            log.warn("event=OWNER_SESSION_REFRESH_DB_FALLBACK exceptionType={} fallback=DB", e.getClass().getSimpleName());
            return sessions.findByTokenHash(hash);
        }
        if (cached.isEmpty()) {
            // 캐시 유실·게시 실패도 DB의 실제 세션 해시와 상태를 확인한 뒤에만 복구한다.
            return sessions.findByTokenHash(hash);
        }
        var data = cached.orElseThrow();
        OwnerSession session = sessions.findBySessionKeyAndOwnerId(data.sessionKey(), data.ownerId()).orElseThrow(this::invalid);
        if (data.revoked() && hash.equals(session.getRefreshTokenHash())) {
            throw invalid();
        }
        return Optional.of(session);
    }

    private void publish(OwnerSession snapshot, String token) {
        OwnerSession current = transactions.execute(tx -> {
            Owner owner = owners.findByIdForLogin(snapshot.getOwnerId()).orElseThrow(this::invalid);
            return sessions.findCurrent(snapshot.getSessionKey(), owner.getId())
                    .filter(session -> owner.canLogin() && session.isActive(owner.getSessionGeneration(), now()))
                    .filter(session -> session.getRotationVersion() == snapshot.getRotationVersion())
                    .filter(session -> TokenHasher.sha256(token).equals(session.getRefreshTokenHash())).orElse(null);
        });
        if (current == null) {
            return;
        }
        Duration remaining = Duration.between(clock.instant(), current.getExpiresAt().atZone(ClockConfig.ZONE).toInstant());
        if (remaining.toMillis() <= 0) {
            throw invalid();
        }
        try {
            tokens.publish(token, current.getOwnerId(), current.getSessionKey(), current.getRotationVersion(), remaining);
        } catch (DataAccessException e) {
            log.warn("event=OWNER_SESSION_REFRESH_CACHE_PUBLISH_FAILED ownerId={} exceptionType={} fallback=KEEP_DB_TOKEN",
                    current.getOwnerId(), e.getClass().getSimpleName());
        }
    }

    private void restoreAfterFailure(OwnerSession snapshot, String hash) {
        try {
            transactions.executeWithoutResult(tx -> owners.findByIdForLogin(snapshot.getOwnerId())
                    .filter(Owner::canLogin).ifPresent(owner -> sessions.findCurrent(snapshot.getSessionKey(), owner.getId())
                            .filter(session -> session.isActive(owner.getSessionGeneration(), now()))
                            .filter(session -> hash.equals(session.getRefreshTokenHash()))
                            .ifPresent(session -> tokens.restore(hash))));
        } catch (DataAccessException | TransactionException e) {
            log.warn("event=OWNER_SESSION_REFRESH_RESTORE_FAILED ownerId={} exceptionType={}",
                    snapshot.getOwnerId(), e.getClass().getSimpleName());
        }
    }

    private void revokeCache(OwnerSession session) {
        long remaining = Duration.between(clock.instant(), session.getExpiresAt().atZone(ClockConfig.ZONE).toInstant()).toMillis();
        Duration ttl = Duration.ofMillis(Math.max(Math.max(remaining, jwt.getAccessTokenValidityMs()), 1L));
        tokens.revoke(session.getSessionKey(), session.getRefreshTokenHash(), session.getRotationVersion(), ttl);
    }

    private String accessToken(Long ownerId, OwnerSession session) {
        return jwt.createAccessToken(ownerId, Role.OWNER, Map.of(SESSION_CLAIM, session.getSessionKey()));
    }

    private OwnerLoginResponse response(Owner owner) {
        return new OwnerLoginResponse(owner.getEmail(), Role.OWNER, owner.getStatus(),
                owner.getStatus() == OwnerStatus.ACTIVE ? owner.isTutorialViewed() : null);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ClockConfig.ZONE);
    }

    private AuthException invalid() {
        return new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
    }

    private record Rotation(OwnerTokenRefreshResult result, boolean reused, OwnerSession session) { }
}
