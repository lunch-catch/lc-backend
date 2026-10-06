package com.launchcatch.member.contract;

import java.math.BigDecimal;

public record MemberInfo(
        Long memberId,
        String nickname,
        MemberStatus status,
        boolean onboardingCompleted,
        boolean notificationOptIn,
        boolean locationOptIn,
        BigDecimal latitude,
        BigDecimal longitude
) {
}
