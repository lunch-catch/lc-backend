package com.launchcatch.billing.dto;

/*
 * 대사가 찾은 예약액 초과 한 건이다(CAMPAIGN_OVERSPEND). 정산 안에서만 쓴다.
 *
 * 해제 전의 전일 대사는 그날 원장 DEDUCT 합이 예약액을 넘은 경우를 담고, 해제 뒤의 대사는 DEDUCT 합과 RELEASE 합의 합이
 * 예약액을 넘은 경우를 담는다. 어느 쪽이든 ledgerSpend 는 예약액에서 빠져나간 금액이다.
 */
public record CampaignOverspend(
        Long campaignId,
        long reserved,
        long ledgerSpend
) {
}
