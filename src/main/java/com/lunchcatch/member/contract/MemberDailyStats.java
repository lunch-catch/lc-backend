package com.lunchcatch.member.contract;

public record MemberDailyStats(
        long newMemberCount,
        long cumulativeMemberCount,
        long withdrawnCount,
        long suspendedCount
) {
}
