package com.launchcatch.member.service;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.exception.MemberErrorCode;
import com.launchcatch.member.exception.MemberException;
import com.launchcatch.member.oauth.KakaoIdTokenExchanger;
import com.launchcatch.member.oauth.KakaoIdentity;
import com.launchcatch.member.repository.MemberProfileRepository;
import com.launchcatch.member.repository.MemberRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberWithdrawalService {
    private final KakaoIdTokenExchanger kakaoIdTokenExchanger;
    private final MemberRepository memberRepository;
    private final MemberProfileRepository memberProfileRepository;
    private final MemberTokenService memberTokenService;
    private final Clock clock;

    @Transactional
    public void withdraw(Long memberId, String authorizationCode, String state) {
        KakaoIdentity identity = kakaoIdTokenExchanger.exchange(authorizationCode, state);
        Member member = memberRepository.findById(memberId).orElseThrow();
        if (member.getStatus() == com.launchcatch.member.contract.MemberStatus.WITHDRAWN) {
            throw new MemberException(MemberErrorCode.ALREADY_WITHDRAWN);
        }
        if (!member.getProviderUserId().equals(identity.providerUserId())) {
            throw new AuthException(AuthErrorCode.KAKAO_AUTHENTICATION_FAILED);
        }
        memberProfileRepository.findByMember_Id(memberId).ifPresent(memberProfileRepository::delete);
        member.withdraw(LocalDateTime.now(clock));
        memberTokenService.revoke(memberId);
    }
}
