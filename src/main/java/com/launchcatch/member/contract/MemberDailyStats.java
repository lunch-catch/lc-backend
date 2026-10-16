package com.launchcatch.member.contract;

public record MemberDailyStats(
        long newMemberCount,
        long cumulativeMemberCount,
        long withdrawnCount,
        long suspendedCount
) {
}
