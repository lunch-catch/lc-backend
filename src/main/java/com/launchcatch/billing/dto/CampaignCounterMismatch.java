package com.launchcatch.billing.dto;

/*
 * 전일 대사가 찾은 당일 소진 카운터와 원장 DEDUCT 합의 차이 한 건이다(CAMPAIGN_SPEND_VS_LEDGER). 정산 안에서만 쓴다.
 */
public record CampaignCounterMismatch(
        Long campaignId,
        long ledgerSpend,
        long counterSpend
) {
}
