package com.launchcatch.analytics.query.repository;

import java.time.LocalDate;

// 회원 지표 조회가 읽는 daily_member_analytics 한 행이다
public record MemberMetricsDailyRow(
        LocalDate businessDate,
        long newMemberCount,
        long cumulativeMemberCount,
        long activeMemberCount,
        long withdrawnCount,
        long suspendedCount
) {
}
