package com.lunchcatch.analytics.query.repository;

/*
 * 기간 안의 캠페인 하나의 부정 경고 원천이다. 유효와 무효 노출은 기간 합계다. 회원 수와 점유율은 상위 회원의
 * 점유율이 가장 높았던 하루의 값이고, 집중 의심은 기간 중 하루라도 의심이었는지를 뜻한다.
 */
public record CampaignFraudRow(
        long campaignId,
        long validCount,
        long invalidCount,
        long distinctMemberCount,
        double topMembersShare,
        boolean concentrationSuspected
) {
}
