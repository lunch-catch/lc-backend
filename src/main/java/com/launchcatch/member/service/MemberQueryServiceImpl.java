package com.launchcatch.member.service;

import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.repository.MemberRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberQueryServiceImpl implements MemberQueryService {

    private final MemberRepository memberRepository;

    @Override
    public Optional<MemberInfo> findById(Long memberId) {
        return memberRepository.findById(memberId)
                .map(member -> new MemberInfo(
                        member.getId(),
                        member.getNickname(),
                        member.getStatus(),
                        member.getProfile() != null && member.getProfile().isOnboardingCompleted(),
                        member.isNotificationOptIn(),
                        member.isLocationOptIn(),
                        member.getProfile() == null ? null : member.getProfile().getLatitude(),
                        member.getProfile() == null ? null : member.getProfile().getLongitude()
                ));
    }
}
