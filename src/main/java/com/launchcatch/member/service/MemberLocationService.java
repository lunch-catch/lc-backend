package com.launchcatch.member.service;

import com.launchcatch.global.logging.TransactionLogging;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
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
        try {
            Member member = lockedMember(memberId);
            if (member.getStatus() == MemberStatus.WITHDRAWN) {
                log.info("event=MEMBER_LOCATION_SAVE_FAILED memberId={} reason=ALREADY_WITHDRAWN", memberId);
                throw new MemberException(MemberErrorCode.ALREADY_WITHDRAWN);
            }
            MemberProfile profile = memberProfileRepository.findByMember_Id(memberId)
                    .orElseGet(() -> memberProfileRepository.save(MemberProfile.create(member)));
            profile.updateLocation(request.locationNickname(), request.roadAddress(), request.latitude(), request.longitude());
            MemberLocationResponse result = response(profile);
            TransactionLogging.afterCommit(() -> log.info("event=MEMBER_LOCATION_SAVE_SUCCESS memberId={}", memberId));
            TransactionLogging.afterFailure(() -> log.error("event=MEMBER_LOCATION_SAVE_FAILED memberId={} reason=TRANSACTION_COMPLETION_FAILED", memberId));
            return result;
        } catch (MemberException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("event=MEMBER_LOCATION_SAVE_FAILED memberId={} reason=SYSTEM_ERROR exceptionType={}", memberId, e.getClass().getSimpleName());
            throw e;
        }
    }

    public MemberLocationResponse get(Long memberId) {
        try {
            return memberProfileRepository.findByMember_Id(memberId)
                    .map(this::response)
                    .orElse(new MemberLocationResponse(null, null, null, null));
        } catch (RuntimeException e) {
            log.error("event=MEMBER_LOCATION_READ_FAILED memberId={} reason=SYSTEM_ERROR exceptionType={}", memberId, e.getClass().getSimpleName());
            throw e;
        }
    }

    @Transactional
    public void delete(Long memberId) {
        try {
            memberProfileRepository.findByMember_Id(memberId).ifPresent(MemberProfile::clearLocation);
            TransactionLogging.afterCommit(() -> log.info("event=MEMBER_LOCATION_DELETE_SUCCESS memberId={}", memberId));
            TransactionLogging.afterFailure(() -> log.error("event=MEMBER_LOCATION_DELETE_FAILED memberId={} reason=TRANSACTION_COMPLETION_FAILED", memberId));
        } catch (RuntimeException e) {
            log.error("event=MEMBER_LOCATION_DELETE_FAILED memberId={} reason=SYSTEM_ERROR exceptionType={}", memberId, e.getClass().getSimpleName());
            throw e;
        }
    }

    private Member lockedMember(Long memberId) {
        return memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new IllegalStateException("인증된 회원 정보를 찾을 수 없습니다."));
    }

    private MemberLocationResponse response(MemberProfile profile) {
        return new MemberLocationResponse(profile.getLocationNickname(), profile.getRoadAddress(),
                profile.getLatitude(), profile.getLongitude());
    }
}
