package com.launchcatch.member.service;

import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.dto.MemberLocationRequest;
import com.launchcatch.member.dto.MemberLocationResponse;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.entity.MemberProfile;
import com.launchcatch.member.exception.MemberErrorCode;
import com.launchcatch.member.exception.MemberException;
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

    /*
     * 회원 행을 잠그고 시작한다. 탈퇴가 프로필을 지우는 같은 행 잠금이라, 잠그지 않으면 탈퇴가 끝난
     * 직후에 이 요청이 프로필을 새로 만들어 탈퇴한 회원의 좌표가 남는다.
     */
    @Transactional
    public MemberLocationResponse save(Long memberId, MemberLocationRequest request) {
        Member member = lockedMember(memberId);
        if (member.getStatus() == MemberStatus.WITHDRAWN) {
            throw new MemberException(MemberErrorCode.ALREADY_WITHDRAWN);
        }
        MemberProfile profile = memberProfileRepository.findByMember_Id(memberId)
                .orElseGet(() -> memberProfileRepository.save(MemberProfile.create(member)));
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

    private Member lockedMember(Long memberId) {
        return memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new IllegalStateException("authenticated member does not exist"));
    }

    private MemberLocationResponse response(MemberProfile profile) {
        return new MemberLocationResponse(profile.getLocationNickname(), profile.getRoadAddress(),
                profile.getLatitude(), profile.getLongitude());
    }
}
