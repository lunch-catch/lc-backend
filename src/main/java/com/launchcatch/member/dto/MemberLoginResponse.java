package com.launchcatch.member.dto;

public record MemberLoginResponse(
        Long memberId,
        String nickname,
        boolean newMember,
        boolean onboardingCompleted
) {
}
