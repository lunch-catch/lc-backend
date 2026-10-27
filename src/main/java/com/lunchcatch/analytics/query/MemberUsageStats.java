package com.lunchcatch.analytics.query;

/*
 * 한 회원의 사용 통계 누적이다. 전날까지 확정된 일별 값의 합이다.
 */
public record MemberUsageStats(long wishCount, long redeemCount, long savedAmount) {
}
