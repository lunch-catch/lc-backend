package com.lunchcatch.billing.deduction.repository;

// 점주와 캠페인별 BILLED 판정의 단가 합이다. amount 는 양수다
public record OwnerCampaignBilledRow(Long ownerId, Long campaignId, Long amount) {
}
