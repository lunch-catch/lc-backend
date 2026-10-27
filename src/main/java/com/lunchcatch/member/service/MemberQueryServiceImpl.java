package com.lunchcatch.member.service;

import com.lunchcatch.member.contract.MemberInfo;
import com.lunchcatch.member.contract.MemberQueryService;
import com.lunchcatch.member.contract.MemberStatus;
import com.lunchcatch.member.entity.Member;
import com.lunchcatch.member.entity.MemberProfile;
import com.lunchcatch.member.repository.MemberRepository;
import com.lunchcatch.member.repository.MemberStatusRow;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
        return memberRepository.findById(memberId).map(this::toInfo);
    }

    @Override
    public Optional<MemberStatus> findCurrentStatus(Long memberId) {
        return memberRepository.findStatusById(memberId);
    }

    @Override
    public Map<Long, MemberStatus> findCurrentStatuses(Set<Long> memberIds) {
        if (memberIds.size() > MAX_FIND_ALL_SIZE) {
            throw new IllegalArgumentException(
                    "memberIds must not exceed " + MAX_FIND_ALL_SIZE + ": " + memberIds.size());
        }
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        return memberRepository.findStatusesByIdIn(memberIds).stream()
                .collect(Collectors.toUnmodifiableMap(MemberStatusRow::memberId, MemberStatusRow::status));
    }

    @Override
    public List<MemberInfo> findAllById(List<Long> memberIds) {
        if (memberIds.size() > MAX_FIND_ALL_SIZE) {
            throw new IllegalArgumentException(
                    "memberIds must not exceed " + MAX_FIND_ALL_SIZE + ": " + memberIds.size());
        }
        if (memberIds.isEmpty()) {
            return List.of();
        }
        return memberRepository.findAllWithProfileByIdIn(memberIds).stream().map(this::toInfo).toList();
    }

    private MemberInfo toInfo(Member member) {
        MemberProfile profile = member.getProfile();
        return new MemberInfo(
                member.getId(),
                member.getNickname(),
                member.getStatus(),
                profile != null && profile.isOnboardingCompleted(),
                member.isNotificationOptIn(),
                member.isLocationOptIn(),
                profile == null ? null : profile.getLatitude(),
                profile == null ? null : profile.getLongitude(),
                profile == null ? null : profile.getGender(),
                profile == null ? null : profile.getAgeGroup()
        );
    }
}
