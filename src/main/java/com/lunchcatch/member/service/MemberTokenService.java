package com.lunchcatch.member.service;

import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.StoreFailures;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.auth.jwt.JwtTokenProvider;
import com.lunchcatch.auth.opaque.OpaqueTokenGenerator;
import com.lunchcatch.auth.opaque.OpaqueRefreshTokenLifecycle;
import com.lunchcatch.global.logging.FailureLogDetails;
import com.lunchcatch.global.logging.TransactionLogging;
import com.lunchcatch.member.client.KakaoLogoutClient;
import com.lunchcatch.member.entity.Member;
import com.lunchcatch.member.repository.MemberRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberTokenService {

    private static final Role ROLE = Role.MEMBER;

    private final JwtTokenProvider jwtTokenProvider;
    private final OpaqueRefreshTokenLifecycle refreshTokenLifecycle;
    private final MemberRepository memberRepository;
    private final KakaoLogoutClient kakaoLogoutClient;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /*
     * 바깥 트랜잭션을 열지 않는다. DB 쓰기는 백업 저장 하나이고 그 쿼리가 자기 트랜잭션을 연다.
     * 바깥에서 열면 커넥션을 얻지 못한 실패가 lifecycle 의 처리 밖에서 나 명세의 AUTH-002 대신 500 이 된다(#1320).
     */
    public TokenPair issue(Member member) {
        String accessToken = jwtTokenProvider.createAccessToken(member.getId(), ROLE);
        String refreshToken = OpaqueTokenGenerator.generate();
        Duration ttl = refreshTtl();
        refreshTokenLifecycle.issue(member.getId(), ROLE, refreshToken, ttl, now());
        return new TokenPair(accessToken, refreshToken, member.getId());
    }

    /*
     * 바깥 트랜잭션을 열지 않는다. DB 일은 모두 lifecycle 이 부르는 백업 저장소의 별도 트랜잭션에서 한다.
     * 바깥에서 열면 DB 일 없이 커넥션 하나를 쥔 채 별도 트랜잭션이 하나를 더 얻어, 재발급이 몰릴 때 풀이 먼저 바닥난다.
     * 커넥션을 얻지 못한 실패도 lifecycle 이 보상한 뒤 AUTH-002 로 바꾼다(#1320).
     */
    public TokenPair reissue(String oldRefreshToken) {
        Long memberId = null;
        try {
            String newRefreshToken = OpaqueTokenGenerator.generate();
            Duration ttl = refreshTtl();
            OpaqueRefreshTokenLifecycle.ReissueResult result =
                    refreshTokenLifecycle.reissue(
                            oldRefreshToken,
                            newRefreshToken,
                            ROLE,
                            ttl,
                            Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()),
                            now());
            memberId = result.subjectId();
            TokenPair tokenPair = new TokenPair(jwtTokenProvider.createAccessToken(result.subjectId(), ROLE), result.refreshToken(), result.subjectId());
            TransactionLogging.afterCommit(() -> log.info("event=MEMBER_TOKEN_REFRESH_SUCCESS memberId={}", result.subjectId()));
            TransactionLogging.afterFailure(() -> log.error("event=MEMBER_TOKEN_REFRESH_FAILED memberId={} reason=TRANSACTION_COMPLETION_FAILED", result.subjectId()));
            return tokenPair;
        } catch (AuthException e) {
            if (e.getErrorCode().isExpectedTraffic()) {
                log.debug("event=MEMBER_TOKEN_REFRESH_FAILED memberId={} errorCode={}", memberId, e.getErrorCode().getCode());
            } else if (e.getErrorCode().getHttpStatus().is5xxServerError()) {
                log.error("event=MEMBER_TOKEN_REFRESH_FAILED memberId={} errorCode={} details={}", memberId, e.getErrorCode().getCode(), FailureLogDetails.from(e));
            }
            throw e;
        } catch (RuntimeException e) {
            log.error("event=MEMBER_TOKEN_REFRESH_FAILED memberId={} reason=SYSTEM_ERROR exceptionType={}", memberId, e.getClass().getSimpleName());
            throw e;
        }
    }

    @Transactional
    public void revoke(Long memberId) {
        refreshTokenLifecycle.revoke(ROLE, memberId, now(), Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()));
    }

    /*
     * 트랜잭션을 try 안에서 연다. @Transactional 이면 커넥션을 얻지 못한 실패가 메서드 밖에서 나 아래 catch 를 거치지 않고
     * 명세의 AUTH-002 대신 500 이 된다(#1320). 기다리면 나아질 실패만 AUTH-002 로 바꾸고 코드 버그는 그대로 던진다.
     * 트랜잭션을 직접 열어 커밋 실패도 아래 catch 로 온다. 그래서 커밋 실패를 따로 기록하지 않는다. 같은 실패를 두 번 남기지 않는다.
     */
    public void logout(Long memberId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                String providerUserId = memberRepository.findById(memberId)
                        .map(Member::getProviderUserId)
                        .orElse(null);
                revoke(memberId);
                TransactionLogging.afterCommit(() -> log.info("event=MEMBER_LOGOUT_SUCCESS memberId={}", memberId));
                if (providerUserId != null) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                kakaoLogoutClient.logout(providerUserId);
                            } catch (RuntimeException e) {
                                String reason = e instanceof CallNotPermittedException
                                        ? "CIRCUIT_OPEN" : "EXTERNAL_REQUEST_FAILED";
                                log.warn("event=KAKAO_LOGOUT_AFTER_COMMIT_FAILED memberId={} reason={} details={}", memberId, reason, FailureLogDetails.from(e));
                            }
                        }
                    });
                }
            });
        } catch (AuthException e) {
            log.error("event=MEMBER_LOGOUT_FAILED memberId={} errorCode={} details={}", memberId, e.getErrorCode().getCode(), FailureLogDetails.from(e));
            throw e;
        } catch (RuntimeException e) {
            log.error("event=MEMBER_LOGOUT_FAILED memberId={} reason=SYSTEM_ERROR exceptionType={}", memberId, e.getClass().getSimpleName());
            throw StoreFailures.toResponse(e);
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
