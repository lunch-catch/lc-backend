package com.lunchcatch.billing.deduction.repository;

// 캠페인별 BILLED 판정의 단가 합과 건수다. amount 는 양수다
public record CampaignBilledRow(Long campaignId, Long amount, Long count) {
}
