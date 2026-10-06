package com.launchcatch.member.contract;

public record MemberInfo(
        Long memberId,
        String nickname,
        MemberStatus status,
        boolean onboardingCompleted,
        boolean notificationOptIn,
        boolean locationOptIn
) {
}
