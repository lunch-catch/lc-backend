package com.launchcatch.member.service;

import com.launchcatch.member.dto.MemberLocationRequest;
import com.launchcatch.member.dto.MemberLocationResponse;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.entity.MemberProfile;
import com.launchcatch.member.repository.MemberProfileRepository;
import com.launchcatch.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberLocationService {

    private final MemberRepository memberRepository;
    private final MemberProfileRepository memberProfileRepository;

    @Transactional
    public MemberLocationResponse save(Long memberId, MemberLocationRequest request) {
        MemberProfile profile = memberProfileRepository.findByMember_Id(memberId)
                .orElseGet(() -> memberProfileRepository.save(MemberProfile.create(member(memberId))));
        profile.updateLocation(request.locationNickname(), request.roadAddress(), request.latitude(), request.longitude());
        return response(profile);
    }

    public MemberLocationResponse get(Long memberId) {
        return memberProfileRepository.findByMember_Id(memberId)
                .map(this::response)
                .orElse(new MemberLocationResponse(null, null, null, null));
    }

    @Transactional
    public void delete(Long memberId) {
        memberProfileRepository.findByMember_Id(memberId).ifPresent(MemberProfile::clearLocation);
    }

    private Member member(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalStateException("authenticated member does not exist"));
    }

    private MemberLocationResponse response(MemberProfile profile) {
        return new MemberLocationResponse(profile.getLocationNickname(), profile.getRoadAddress(),
                profile.getLatitude(), profile.getLongitude());
    }
}
