package com.launchcatch.analytics.query;

/*
 * 회원 지표 시계열의 한 구간이다. 집계 전(IN_PROGRESS)이면 수치는 모두 null 이다. 집계된 0 과 구별하려는
 * 것이다. 스냅샷 지표는 구간 마지막 날의 행이 없으면 null 이다.
 */
public record MemberMetricsBucket(
        String label,
        AggregationStatus status,
        Long newMemberCount,
        Long cumulativeMemberCount,
        Long avgDailyActiveMembers,
        Long withdrawnCount,
        Long suspendedCount
) {
}
