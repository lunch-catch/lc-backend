package com.launchcatch.campaign.contract;

import java.time.LocalDate;
import java.time.LocalDateTime;

/*
 * 캠페인 상태가 바뀌었다. 광고 서빙, 쿠폰, 정산이 받는다.
 * 받는 쪽은 자기가 가진 시각보다 changedAt 이 늦은 것만 반영한다.
 */
public record CampaignStatusChangedEvent(
        long campaignId,
        LocalDate businessDate,
        boolean servable,
        LocalDateTime changedAt) {
}
