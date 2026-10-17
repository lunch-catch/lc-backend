package com.launchcatch.analytics.batch;

/*
 * 하루 회원 지표다. 신규, 누적, 탈퇴는 회원 집계 조회가 주고 활성 회원은 event_log 에서 센다.
 * 정지 회원은 정지 기능이 구현 제외라 저장할 때 0 을 채운다.
 */
public record MemberMetrics(
        long newMemberCount,
        long cumulativeMemberCount,
        long withdrawnCount,
        long activeMemberCount
) {
}
