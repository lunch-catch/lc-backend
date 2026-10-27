package com.lunchcatch.analytics.batch;

import java.util.List;

/*
 * 하루 캠페인별 부정 경고 원천이다. 유효 노출이나 무효 노출이 있는 캠페인마다 한 행이다. 회원 수와 상위 회원의
 * 노출 합은 유효 노출만 센다.
 */
public record FraudMetrics(List<Row> rows) {

    public record Row(
            long campaignId,
            long validCount,
            long invalidCount,
            long distinctMemberCount,
            long topMembersCount,
            boolean concentrationSuspected
    ) {
    }
}
