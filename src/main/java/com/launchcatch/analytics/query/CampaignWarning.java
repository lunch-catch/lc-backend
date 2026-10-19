package com.launchcatch.analytics.query;

/*
 * 기간 안의 캠페인 하나의 경고 판정이다. invalidRatio 는 유효 노출이 0이면 null 이다. 회원 수와 점유율은 상위
 * 회원의 점유율이 가장 높았던 하루의 값이다.
 */
public record CampaignWarning(
        long campaignId,
        long validCount,
        long invalidCount,
        Double invalidRatio,
        boolean invalidRatioWarning,
        long distinctMemberCount,
        double topMembersShare,
        boolean concentrationWarning
) {
}
