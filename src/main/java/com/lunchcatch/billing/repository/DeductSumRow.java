package com.lunchcatch.billing.repository;

// 하루 동안의 캠페인별 DEDUCT 합과 건수다. amount 는 부호가 있는 저장값의 합이라 음수다
public record DeductSumRow(Long campaignId, Long amount, Long count) {
}
