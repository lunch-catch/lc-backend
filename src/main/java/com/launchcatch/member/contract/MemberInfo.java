package com.launchcatch.member.contract;

import java.math.BigDecimal;

/**
 * 다른 도메인이 읽는 회원 정보다.
 * gender 와 ageGroup 은 프로필이 없는 회원(온보딩 전, 탈퇴)이면 null 이다.
 */
public record MemberInfo(
        Long memberId,
        String nickname,
        MemberStatus status,
        boolean onboardingCompleted,
        boolean notificationOptIn,
        boolean locationOptIn,
        BigDecimal latitude,
        BigDecimal longitude,
        Gender gender,
        AgeGroup ageGroup
) {
}
