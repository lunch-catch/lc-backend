package com.launchcatch.member.dto;

import com.launchcatch.member.contract.MemberStatus;
import java.time.OffsetDateTime;

// provider_user_id 는 어떤 필드로도 내리지 않는다
public record AdminMemberResponse(
        Long memberId,
        String nickname,
        MemberStatus status,
        OffsetDateTime joinedAt,
        OffsetDateTime lastLoginAt
) {
}
