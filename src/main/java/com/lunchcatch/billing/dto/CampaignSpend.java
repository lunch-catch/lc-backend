package com.lunchcatch.billing.dto;

/**
 * 캠페인 하나의 그날 소진액이다. 판정의 그날 BILLED 단가 합을 양수로 담는다. RELEASE 합을 담을 때도 쓴다.
 *
 * @param campaignId 캠페인 ID
 * @param spend      그날 소진액
 */
public record CampaignSpend(Long campaignId, Long spend) {
}
