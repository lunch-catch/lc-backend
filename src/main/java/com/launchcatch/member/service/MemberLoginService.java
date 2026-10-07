package com.launchcatch.member.service;

import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.exception.MemberErrorCode;
import com.launchcatch.member.exception.MemberException;
import com.launchcatch.member.oauth.KakaoIdTokenExchanger;
import com.launchcatch.member.oauth.KakaoIdentity;
import com.launchcatch.member.repository.KakaoUnlinkFailureRepository;
import com.launchcatch.member.repository.MemberProfileRepository;
import com.launchcatch.member.repository.MemberRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class MemberLoginService {

    private final KakaoIdTokenExchanger kakaoIdTokenExchanger;
    private final MemberRepository memberRepository;
    private final MemberProfileRepository memberProfileRepository;
    private final KakaoUnlinkFailureRepository kakaoUnlinkFailureRepository;
    private final MemberTokenService memberTokenService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public LoginResult login(String authorizationCode, String state) {
        KakaoIdentity identity = kakaoIdTokenExchanger.exchange(authorizationCode, state);
        ResolvedMember resolvedMember = resolveMember(identity);
        MemberTokenService.TokenPair tokenPair = memberTokenService.issue(resolvedMember.member());
        boolean onboardingCompleted = memberProfileRepository
                .existsByMember_IdAndOnboardingCompletedAtIsNotNull(resolvedMember.member().getId());
        return new LoginResult(resolvedMember.member(), resolvedMember.newMember(), onboardingCompleted, tokenPair);
    }

    private ResolvedMember resolveMember(KakaoIdentity identity) {
        try {
            return transactionTemplate.execute(status -> findOrCreate(identity));
        } catch (DataIntegrityViolationException e) {
            return transactionTemplate.execute(status -> memberRepository.findByProviderUserId(identity.providerUserId())
                    .map(member -> enterExisting(member, identity))
                    .orElseThrow(() -> e));
        }
    }

    private ResolvedMember findOrCreate(KakaoIdentity identity) {
        return memberRepository.findByProviderUserId(identity.providerUserId())
                .map(member -> enterExisting(member, identity))
                .orElseGet(() -> register(identity));
    }

    private ResolvedMember register(KakaoIdentity identity) {
        Member member = Member.create(identity.providerUserId(), identity.nickname(), identity.profileImageUrl());
        member.recordLogin(LocalDateTime.now(clock));
        return new ResolvedMember(memberRepository.saveAndFlush(member), true);
    }

    private ResolvedMember enterExisting(Member member, KakaoIdentity identity) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (member.getStatus() == MemberStatus.WITHDRAWN) {
            if (member.hasSuspensionHistory()) {
                throw new MemberException(MemberErrorCode.SUSPENDED_REJOIN_NOT_ALLOWED);
            }
            member.reactivate(identity.nickname(), identity.profileImageUrl(), now);
            kakaoUnlinkFailureRepository.deleteByMember_Id(member.getId());
            return new ResolvedMember(member, true);
        }
        if (member.getStatus() != MemberStatus.ACTIVE) {
            throw new MemberException(MemberErrorCode.SUSPENDED_REJOIN_NOT_ALLOWED);
        }
        member.recordLogin(now);
        return new ResolvedMember(member, false);
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
