package com.launchcatch.member.service;

import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.dto.MemberOnboardingRequest;
import com.launchcatch.member.dto.MemberResponse;
import com.launchcatch.member.dto.MemberUpdateRequest;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.entity.MemberProfile;
import com.launchcatch.member.exception.MemberErrorCode;
import com.launchcatch.member.exception.MemberException;
import com.launchcatch.member.repository.MemberProfileRepository;
import com.launchcatch.member.repository.MemberRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberProfileService {

    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");

    private final MemberRepository memberRepository;
    private final MemberProfileRepository memberProfileRepository;
    private final Clock clock;

    /*
     * 쓰기 경로는 회원 행을 잠그고 읽는다. 탈퇴가 같은 행을 잠그고 처리하므로(MemberWithdrawalService)
     * 잠금 없이 읽으면 탈퇴 직전의 ACTIVE 값을 들고 저장해 탈퇴를 되돌리거나 탈퇴한 회원에게
     * 프로필을 만든다. 동시에 들어온 온보딩 둘이 모두 "프로필 없음" 으로 보고 insert 하는 경우도
     * 잠금으로 순서가 생겨 두 번째가 409 로 거절된다.
     */
    @Transactional
    public MemberResponse completeOnboarding(Long memberId, MemberOnboardingRequest request) {
        Member member = findMemberForUpdate(memberId);
        ensureNotWithdrawn(member);
        if (member.getProfile() != null && member.getProfile().isOnboardingCompleted()) {
            throw new MemberException(MemberErrorCode.ONBOARDING_ALREADY_COMPLETED);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        MemberProfile profile = member.getProfile();
        if (profile == null) {
            profile = memberProfileRepository.save(MemberProfile.create(member));
        }
        profile.completeOnboarding(request.gender(), request.ageGroup(), now);
        member.completeOnboarding(request.notificationOptIn(), request.locationOptIn(), now);
        return response(member);
    }

    public MemberResponse getMyProfile(Long memberId) {
        Member member = findMember(memberId);
        ensureNotWithdrawn(member);
        return response(member);
    }

    @Transactional
    public MemberResponse updateMyProfile(Long memberId, MemberUpdateRequest request) {
        if (!request.hasUpdate()) {
            throw new IllegalArgumentException("at least one field must be provided");
        }
        Member member = findMemberForUpdate(memberId);
        ensureNotWithdrawn(member);
        member.updateProfile(request.nickname(), request.notificationOptIn(), request.locationOptIn(), LocalDateTime.now(clock));
        return response(member);
    }

    private Member findMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalStateException("authenticated member does not exist"));
    }

    private Member findMemberForUpdate(Long memberId) {
        return memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new IllegalStateException("authenticated member does not exist"));
    }

    private void ensureNotWithdrawn(Member member) {
        if (member.getStatus() == MemberStatus.WITHDRAWN) {
            throw new MemberException(MemberErrorCode.ALREADY_WITHDRAWN);
        }
    }

    private MemberResponse response(Member member) {
        MemberProfile profile = member.getProfile();
        boolean onboardingCompleted = profile != null && profile.isOnboardingCompleted();
        MemberResponse.Profile profileResponse = !onboardingCompleted ? null
                : new MemberResponse.Profile(profile.getGender(), profile.getAgeGroup());
        return new MemberResponse(
                member.getId(), member.getNickname(), member.getProfileImageUrl(), member.getStatus(),
                onboardingCompleted, onboardingCompleted && member.isLocationOptIn(), profileResponse,
                new MemberResponse.Consents(member.isNotificationOptIn(), offset(member.getNotificationOptInAt()),
                        member.isLocationOptIn(), offset(member.getLocationOptInAt())),
                offset(member.getCreatedAt()));
    }

    private OffsetDateTime offset(LocalDateTime time) {
        return time == null ? null : time.atZone(KOREA_ZONE).toOffsetDateTime();
    }
}
