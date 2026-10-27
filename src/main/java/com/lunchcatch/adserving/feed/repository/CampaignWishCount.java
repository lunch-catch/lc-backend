package com.lunchcatch.adserving.feed.repository;

// 하루 동안 한 캠페인의 카드를 찜한 횟수다.
public record CampaignWishCount(Long campaignId, long count) {
}
