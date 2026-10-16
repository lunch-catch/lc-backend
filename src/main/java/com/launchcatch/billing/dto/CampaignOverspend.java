package com.launchcatch.billing.dto;

/*
 * 전일 대사가 찾은 예약액 초과 소진 한 건이다(CAMPAIGN_OVERSPEND). 그날 원장 DEDUCT 합이 그날 예약액을 넘었다.
 * 정산 안에서만 쓴다.
 */
public record CampaignOverspend(
        Long campaignId,
        long reserved,
        long ledgerSpend
) {
}
