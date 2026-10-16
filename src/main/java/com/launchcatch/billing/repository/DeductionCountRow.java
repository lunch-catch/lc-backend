package com.launchcatch.billing.repository;

import com.launchcatch.billing.contract.DeductionStatus;

// 캠페인과 과금 상태별 판정 건수다
public record DeductionCountRow(Long campaignId, DeductionStatus status, Long count) {
}
