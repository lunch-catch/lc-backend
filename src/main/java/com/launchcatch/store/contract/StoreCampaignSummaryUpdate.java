package com.launchcatch.store.contract;

// 일괄 저장에서 각 캠페인의 가게 ID와 캠페인 ID를 기존 요약 command와 함께 전달한다.
public record StoreCampaignSummaryUpdate(
        long storeId, long campaignId, StoreCampaignSummaryCommand command
) { }
