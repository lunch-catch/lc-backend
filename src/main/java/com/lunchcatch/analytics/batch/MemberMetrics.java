package com.lunchcatch.analytics.batch;

/*
 * 하루 회원 지표다. 신규, 누적, 탈퇴, 정지는 회원 집계 조회가 주고 활성 회원은 event_log 에서 센다.
 * 정지 회원은 그날 말에 정지 상태인 회원 수다.
 */
public record MemberMetrics(
        long newMemberCount,
        long cumulativeMemberCount,
        long withdrawnCount,
        long activeMemberCount,
        long suspendedCount
) {
}
