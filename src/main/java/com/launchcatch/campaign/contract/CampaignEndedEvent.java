package com.launchcatch.campaign.contract;

import java.time.LocalDateTime;

/*
 * 캠페인이 종료됐다.
 * 광고 서빙은 찜 목록에서 빼고 쿠폰은 발급 회차를 끝낸다.
 */
public record CampaignEndedEvent(long campaignId, LocalDateTime changedAt) {
}
