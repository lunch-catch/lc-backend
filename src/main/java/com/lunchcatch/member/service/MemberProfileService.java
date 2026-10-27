package com.lunchcatch.member.service;

import com.lunchcatch.global.logging.TransactionLogging;
import com.lunchcatch.member.contract.MemberStatus;
import com.lunchcatch.member.dto.MemberOnboardingRequest;
import com.lunchcatch.member.dto.MemberResponse;
import com.lunchcatch.member.dto.MemberUpdateRequest;
import com.lunchcatch.member.entity.Member;
import com.lunchcatch.member.entity.MemberProfile;
import com.lunchcatch.member.exception.MemberErrorCode;
import com.lunchcatch.member.exception.MemberException;
import com.lunchcatch.member.repository.MemberProfileRepository;
import com.lunchcatch.member.repository.MemberRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
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
        try {
            Member member = findMemberForUpdate(memberId);
            if (member.getStatus() == MemberStatus.WITHDRAWN) {
                log.info("event=MEMBER_ONBOARDING_FAILED memberId={} reason=ALREADY_WITHDRAWN", memberId);
                throw new MemberException(MemberErrorCode.ALREADY_WITHDRAWN);
            }
            if (member.getProfile() != null && member.getProfile().isOnboardingCompleted()) {
                log.info("event=MEMBER_ONBOARDING_FAILED memberId={} reason=ONBOARDING_ALREADY_COMPLETED", memberId);
                throw new MemberException(MemberErrorCode.ONBOARDING_ALREADY_COMPLETED);
            }
            LocalDateTime now = LocalDateTime.now(clock);
            MemberProfile profile = member.getProfile();
            if (profile == null) {
                profile = memberProfileRepository.save(MemberProfile.create(member));
            }
            profile.completeOnboarding(request.gender(), request.ageGroup(), now);
            member.completeOnboarding(request.notificationOptIn(), request.locationOptIn(), now);
            MemberResponse result = response(member);
            TransactionLogging.afterCommit(() -> log.info("event=MEMBER_ONBOARDING_SUCCESS memberId={}", memberId));
            TransactionLogging.afterFailure(() -> log.error("event=MEMBER_ONBOARDING_FAILED memberId={} reason=TRANSACTION_COMPLETION_FAILED", memberId));
            return result;
        } catch (MemberException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            log.info("event=MEMBER_ONBOARDING_FAILED memberId={} reason=INVALID_INPUT exceptionType={}", memberId, e.getClass().getSimpleName());
            throw e;
        } catch (RuntimeException e) {
            log.error("event=MEMBER_ONBOARDING_FAILED memberId={} reason=SYSTEM_ERROR exceptionType={}", memberId, e.getClass().getSimpleName());
            throw e;
        }
    }

    public MemberResponse getMyProfile(Long memberId) {
        try {
            Member member = findMember(memberId);
            if (member.getStatus() == MemberStatus.WITHDRAWN) {
                log.info("event=MEMBER_PROFILE_READ_FAILED memberId={} reason=ALREADY_WITHDRAWN", memberId);
                throw new MemberException(MemberErrorCode.ALREADY_WITHDRAWN);
            }
            return response(member);
        } catch (MemberException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("event=MEMBER_PROFILE_READ_FAILED memberId={} reason=SYSTEM_ERROR exceptionType={}", memberId, e.getClass().getSimpleName());
            throw e;
        }
    }

    @Transactional
    public MemberResponse updateMyProfile(Long memberId, MemberUpdateRequest request) {
        if (!request.hasUpdate()) {
            log.info("event=MEMBER_PROFILE_UPDATE_FAILED memberId={} reason=EMPTY_UPDATE", memberId);
            throw new IllegalArgumentException("수정할 항목을 하나 이상 입력해 주세요.");
        }
        try {
            Member member = findMemberForUpdate(memberId);
            if (member.getStatus() == MemberStatus.WITHDRAWN) {
                log.info("event=MEMBER_PROFILE_UPDATE_FAILED memberId={} reason=ALREADY_WITHDRAWN", memberId);
                throw new MemberException(MemberErrorCode.ALREADY_WITHDRAWN);
            }
            member.updateProfile(request.nickname(), request.notificationOptIn(), request.locationOptIn(), LocalDateTime.now(clock));
            MemberResponse result = response(member);
            TransactionLogging.afterCommit(() -> log.info("event=MEMBER_PROFILE_UPDATE_SUCCESS memberId={}", memberId));
            TransactionLogging.afterFailure(() -> log.error("event=MEMBER_PROFILE_UPDATE_FAILED memberId={} reason=TRANSACTION_COMPLETION_FAILED", memberId));
            return result;
        } catch (MemberException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            log.info("event=MEMBER_PROFILE_UPDATE_FAILED memberId={} reason=INVALID_INPUT exceptionType={}", memberId, e.getClass().getSimpleName());
            throw e;
        } catch (RuntimeException e) {
            log.error("event=MEMBER_PROFILE_UPDATE_FAILED memberId={} reason=SYSTEM_ERROR exceptionType={}", memberId, e.getClass().getSimpleName());
            throw e;
        }
    }

    private Member findMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalStateException("인증된 회원 정보를 찾을 수 없습니다."));
    }

    private Member findMemberForUpdate(Long memberId) {
        return memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new IllegalStateException("인증된 회원 정보를 찾을 수 없습니다."));
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
