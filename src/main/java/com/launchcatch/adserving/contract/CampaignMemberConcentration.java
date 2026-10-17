package com.launchcatch.adserving.contract;

/*
 * 한 영업일에 캠페인 하나의 유효 노출이 회원에게 얼마나 몰렸는지다. 유효 노출만 센다.
 * topMembersValidCount 는 유효 노출이 많은 순으로 상위 N명의 노출 합이다. 회원이 N명보다 적으면 그 캠페인의 유효 노출 전부다.
 */
public record CampaignMemberConcentration(
        Long campaignId,
        long distinctMemberCount,
        long topMembersValidCount
) {
}
