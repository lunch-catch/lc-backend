package com.launchcatch.member.service;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.OpaqueTokenGenerator;
import com.launchcatch.auth.opaque.OpaqueRefreshTokenLifecycle;
import com.launchcatch.member.client.KakaoLogoutClient;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.repository.MemberRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final OpaqueRefreshTokenLifecycle refreshTokenLifecycle;
    private final MemberRepository memberRepository;
    private final KakaoLogoutClient kakaoLogoutClient;
    private final Clock clock;

    @Transactional
    public TokenPair issue(Member member) {
        String accessToken = jwtTokenProvider.createAccessToken(member.getId(), ROLE);
        String refreshToken = OpaqueTokenGenerator.generate();
        Duration ttl = refreshTtl();
        refreshTokenLifecycle.issue(member.getId(), ROLE, refreshToken, ttl, now());
        return new TokenPair(accessToken, refreshToken, member.getId());
    }

    @Transactional(noRollbackFor = AuthException.class)
    public TokenPair reissue(String oldRefreshToken) {
        String newRefreshToken = OpaqueTokenGenerator.generate();
        Duration ttl = refreshTtl();
        OpaqueRefreshTokenLifecycle.ReissueResult result =
                refreshTokenLifecycle.reissue(oldRefreshToken, newRefreshToken, ROLE, ttl, now());
        return new TokenPair(jwtTokenProvider.createAccessToken(result.subjectId(), ROLE), result.refreshToken(), result.subjectId());
    }

    @Transactional
    public void revoke(Long memberId) {
        refreshTokenLifecycle.revoke(ROLE, memberId, now(), Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()));
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
                    try {
                        kakaoLogoutClient.logout(providerUserId);
                    } catch (RuntimeException e) {
                        log.warn("event=KAKAO_LOGOUT_AFTER_COMMIT_FAILED memberId={}", memberId, e);
                    }
                }
            });
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
