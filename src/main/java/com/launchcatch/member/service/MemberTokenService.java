package com.launchcatch.member.service;

import com.launchcatch.auth.RedisFailureClassifier;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.OpaqueTokenGenerator;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.TokenHasher;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.client.KakaoLogoutClient;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.repository.MemberRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberTokenService {

    private static final Role ROLE = Role.MEMBER;

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessTokenValidAfterRepository accessTokenValidAfterRepository;
    private final MemberRepository memberRepository;
    private final KakaoLogoutClient kakaoLogoutClient;
    private final Clock clock;

    @Transactional
    public TokenPair issue(Member member) {
        String accessToken = jwtTokenProvider.createAccessToken(member.getId(), ROLE);
        String refreshToken = OpaqueTokenGenerator.generate();
        Duration ttl = refreshTtl();
        LocalDateTime now = now();

        saveBackupOrThrow(member.getId(), TokenHasher.sha256(refreshToken), now.plus(ttl), now);
        try {
            refreshTokenRepository.save(refreshToken, member.getId(), ROLE, true, ttl);
        } catch (DataAccessException e) {
            log.warn("event=MEMBER_REFRESH_CACHE_SAVE_FAILED memberId={} cause={}",
                    member.getId(), RedisFailureClassifier.causeLabel(e), e);
        }
        return new TokenPair(accessToken, refreshToken, member.getId());
    }

    @Transactional
    public TokenPair reissue(String oldRefreshToken) {
        String newRefreshToken = OpaqueTokenGenerator.generate();
        LocalDateTime now = now();
        Duration ttl = refreshTtl();
        try {
            RefreshTokenRepository.RotateOutcome outcome =
                    refreshTokenRepository.compareAndRotate(oldRefreshToken, newRefreshToken, ttl);
            return reissueFromCache(oldRefreshToken, newRefreshToken, outcome, now, ttl);
        } catch (DataAccessException e) {
            log.warn("event=MEMBER_REFRESH_CACHE_ROTATE_FAILED cause={}",
                    RedisFailureClassifier.causeLabel(e), e);
            return reissueFromDatabase(oldRefreshToken, newRefreshToken, now, ttl);
        }
    }

    @Transactional
    public void revoke(Long memberId) {
        String tokenHash = findCurrentHash(memberId);
        LocalDateTime now = now();
        try {
            if (tokenHash != null) {
                int cleared = memberRepository.clearRefreshTokenBackupIfHashMatches(memberId, tokenHash, now);
                if (cleared == 0) {
                    throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
                }
                refreshTokenRepository.revokeIfActiveHashMatches(tokenHash, ROLE, memberId);
            } else {
                refreshTokenRepository.deleteActiveKey(ROLE, memberId);
            }
            accessTokenValidAfterRepository.invalidateBefore(
                    ROLE, memberId, now, Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()));
        } catch (DataAccessException e) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
        }
    }

    @Transactional
    public void logout(Long memberId) {
        String providerUserId = memberRepository.findById(memberId)
                .map(Member::getProviderUserId)
                .orElse(null);
        revoke(memberId);
        if (providerUserId != null) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    kakaoLogoutClient.logout(providerUserId);
                }
            });
        }
    }

    private TokenPair reissueFromCache(
            String oldRefreshToken,
            String newRefreshToken,
            RefreshTokenRepository.RotateOutcome outcome,
            LocalDateTime now,
            Duration ttl
    ) {
        if (outcome.isReuseDetected()) {
            revoke(outcome.data().id());
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_REUSED);
        }
        if (!outcome.isSuccess() || outcome.data().role() != ROLE) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }

        Long memberId = outcome.data().id();
        Member member = memberRepository.findById(memberId)
                .filter(found -> found.getStatus() == MemberStatus.ACTIVE)
                .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID));
        String oldHash = TokenHasher.sha256(oldRefreshToken);
        String newHash = TokenHasher.sha256(newRefreshToken);
        if (memberRepository.rotateRefreshTokenBackupIfMatches(
                memberId, oldHash, newHash, now.plus(ttl), now, now, MemberStatus.ACTIVE) == 0) {
            compensateCacheRotation(newHash, memberId);
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        }
        return new TokenPair(jwtTokenProvider.createAccessToken(memberId, ROLE), newRefreshToken, memberId);
    }

    private TokenPair reissueFromDatabase(
            String oldRefreshToken, String newRefreshToken, LocalDateTime now, Duration ttl) {
        String oldHash = TokenHasher.sha256(oldRefreshToken);
        Member member = memberRepository.findByRefreshTokenHash(oldHash)
                .filter(found -> found.getStatus() == MemberStatus.ACTIVE)
                .filter(found -> found.getRefreshTokenExpiresAt() != null
                        && found.getRefreshTokenExpiresAt().isAfter(now))
                .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID));

        String newHash = TokenHasher.sha256(newRefreshToken);
        if (memberRepository.rotateRefreshTokenBackupIfMatches(
                member.getId(), oldHash, newHash, now.plus(ttl), now, now, MemberStatus.ACTIVE) == 0) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        try {
            refreshTokenRepository.save(newRefreshToken, member.getId(), ROLE, true, ttl);
        } catch (DataAccessException e) {
            log.warn("event=MEMBER_REFRESH_CACHE_SAVE_AFTER_DB_FALLBACK_FAILED memberId={} cause={}",
                    member.getId(), RedisFailureClassifier.causeLabel(e), e);
        }
        return new TokenPair(jwtTokenProvider.createAccessToken(member.getId(), ROLE), newRefreshToken, member.getId());
    }

    private void saveBackupOrThrow(Long memberId, String tokenHash, LocalDateTime expiresAt, LocalDateTime now) {
        try {
            if (memberRepository.updateRefreshTokenBackup(memberId, tokenHash, expiresAt, now) != 1) {
                throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
            }
        } catch (DataAccessException e) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
        }
    }

    private String findCurrentHash(Long memberId) {
        try {
            Optional<String> cached = refreshTokenRepository.findActiveHash(ROLE, memberId);
            if (cached.isPresent()) {
                return cached.get();
            }
        } catch (DataAccessException e) {
            log.warn("event=MEMBER_ACTIVE_REFRESH_CACHE_LOOKUP_FAILED memberId={} cause={}",
                    memberId, RedisFailureClassifier.causeLabel(e), e);
        }
        return memberRepository.findById(memberId).map(Member::getRefreshTokenHash).orElse(null);
    }

    private void compensateCacheRotation(String newHash, Long memberId) {
        try {
            refreshTokenRepository.revokeIfActiveHashMatches(newHash, ROLE, memberId);
        } catch (DataAccessException e) {
            log.warn("event=MEMBER_REFRESH_CACHE_ROTATION_COMPENSATION_FAILED memberId={} cause={}",
                    memberId, RedisFailureClassifier.causeLabel(e), e);
        }
    }

    private Duration refreshTtl() {
        return Duration.ofMillis(jwtTokenProvider.refreshTokenValidityMs(ROLE));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public record TokenPair(String accessToken, String refreshToken, Long memberId) {
    }
}
