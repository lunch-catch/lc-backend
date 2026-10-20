package com.launchcatch.member.service;

import com.launchcatch.global.exception.BusinessException;
import com.launchcatch.global.logging.FailureLogDetails;
import com.launchcatch.global.logging.TransactionLogging;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.exception.MemberErrorCode;
import com.launchcatch.member.exception.MemberException;
import com.launchcatch.member.oauth.KakaoIdTokenExchanger;
import com.launchcatch.member.oauth.KakaoIdentity;
import com.launchcatch.member.repository.KakaoUnlinkFailureRepository;
import com.launchcatch.member.repository.MemberProfileRepository;
import com.launchcatch.member.repository.MemberRepository;
import com.launchcatch.member.repository.MemberWithdrawalHistoryRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberLoginService {

    private final KakaoIdTokenExchanger kakaoIdTokenExchanger;
    private final MemberRepository memberRepository;
    private final MemberProfileRepository memberProfileRepository;
    private final KakaoUnlinkFailureRepository kakaoUnlinkFailureRepository;
    private final MemberWithdrawalHistoryRepository withdrawalHistoryRepository;
    private final MemberTokenService memberTokenService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public LoginResult login(String authorizationCode, String state) {
        LoginAttempt attempt = new LoginAttempt();
        try {
            KakaoIdentity identity = kakaoIdTokenExchanger.exchange(authorizationCode, state);
            ResolvedMember resolvedMember = resolveMember(identity, attempt);
            Long memberId = resolvedMember.member().getId();
            MemberTokenService.TokenPair tokenPair = memberTokenService.issue(resolvedMember.member());
            boolean onboardingCompleted = memberProfileRepository
                    .existsByMember_IdAndOnboardingCompletedAtIsNotNull(memberId);
            Long loggedMemberId = memberId;
            TransactionLogging.afterCommit(() -> log.info("event=MEMBER_LOGIN_SUCCESS memberId={}", loggedMemberId));
            return new LoginResult(resolvedMember.member(), resolvedMember.newMember(), onboardingCompleted, tokenPair);
        } catch (BusinessException e) {
            if (e.getErrorCode().getHttpStatus().is5xxServerError()) {
                log.error("event=MEMBER_LOGIN_FAILED memberId={} reason={} details={}", attempt.memberId, e.getErrorCode().getCode(), FailureLogDetails.from(e));
            } else {
                log.warn("event=MEMBER_LOGIN_FAILED memberId={} reason={}", attempt.memberId, e.getErrorCode().getCode());
            }
            throw e;
        } catch (RuntimeException e) {
            log.error("event=MEMBER_LOGIN_FAILED memberId={} reason=SYSTEM_ERROR causeType={}", attempt.memberId, e.getClass().getSimpleName());
            throw e;
        }
    }

    private ResolvedMember resolveMember(KakaoIdentity identity, LoginAttempt attempt) {
        // 없는 카카오 번호의 동시 삽입은 InnoDB 갭 잠금에서 교착할 수 있어 새 트랜잭션으로 세 번까지 판정한다.
        for (int retry = 0; ; retry++) {
            try {
                return transactionTemplate.execute(status -> findOrCreate(identity, attempt));
            } catch (PessimisticLockingFailureException e) {
                if (retry >= 2) {
                    throw e;
                }
            } catch (DataIntegrityViolationException e) {
                return transactionTemplate.execute(status -> memberRepository.findByProviderUserIdForUpdate(identity.providerUserId())
                        .map(member -> enterExisting(member, attempt))
                        .orElseThrow(() -> e));
            }
        }
    }

    private ResolvedMember findOrCreate(KakaoIdentity identity, LoginAttempt attempt) {
        return memberRepository.findByProviderUserIdForUpdate(identity.providerUserId())
                .map(member -> enterExisting(member, attempt))
                .orElseGet(() -> register(identity, attempt));
    }

    private ResolvedMember register(KakaoIdentity identity, LoginAttempt attempt) {
        validateRegistration(identity.providerUserId());
        Member member = Member.create(identity.providerUserId(), identity.nickname(), identity.profileImageUrl());
        member.recordLogin(LocalDateTime.now(clock));
        Member saved = memberRepository.saveAndFlush(member);
        attempt.memberId = saved.getId();
        Long memberId = saved.getId();
        TransactionLogging.afterCommit(() -> log.info("event=MEMBER_REGISTRATION_SUCCESS memberId={}", memberId));
        return new ResolvedMember(saved, true);
    }

    private ResolvedMember enterExisting(Member member, LoginAttempt attempt) {
        attempt.memberId = member.getId();
        LocalDateTime now = LocalDateTime.now(clock);
        if (member.getStatus() != MemberStatus.ACTIVE) {
            throw new MemberException(MemberErrorCode.SUSPENDED_REJOIN_NOT_ALLOWED);
        }
        member.recordLogin(now);
        return new ResolvedMember(member, false);
    }

    private void validateRegistration(String providerUserId) {
        var history = withdrawalHistoryRepository.findFirstByProviderUserIdOrderByWithdrawnAtDescIdDesc(providerUserId);
        if (withdrawalHistoryRepository.existsByProviderUserIdAndSuspendedAtIsNotNull(providerUserId)) {
            throw new MemberException(MemberErrorCode.SUSPENDED_REJOIN_NOT_ALLOWED);
        }
        if (history.isPresent() && !history.get().getWithdrawnAt().toLocalDate()
                .isBefore(LocalDateTime.now(clock).toLocalDate())) {
            throw new MemberException(MemberErrorCode.SAME_DAY_REJOIN_NOT_ALLOWED);
        }
        // 포기한 작업도 해제 성공은 아니므로 resolved 여부와 무관하게 남은 행 전체가 재가입을 막는다.
        if (kakaoUnlinkFailureRepository.existsByProviderUserId(providerUserId)) {
            throw new MemberException(MemberErrorCode.UNLINK_PENDING);
        }
    }

    // 로그인 시도마다 회원을 확인한 즉시 식별자를 보존해 이후 실패 로그에서도 사용한다.
    private static final class LoginAttempt {
        private Long memberId;
    }

    private record ResolvedMember(Member member, boolean newMember) {
    }

    public record LoginResult(
            Member member,
            boolean newMember,
            boolean onboardingCompleted,
            MemberTokenService.TokenPair tokenPair
    ) {
    }
}
