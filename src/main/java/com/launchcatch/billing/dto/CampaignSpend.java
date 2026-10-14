package com.launchcatch.billing.dto;

/**
 * 캠페인 하나의 그날 소진액이다. 원장의 그날 DEDUCT 합을 양수로 담는다.
 *
 * @param campaignId 캠페인 ID
 * @param spend      그날 소진액
 */
public record CampaignSpend(Long campaignId, Long spend) {
}
