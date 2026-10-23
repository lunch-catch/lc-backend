package com.launchcatch.adserving.engine.repository;

// 그날 후보 하나의 예약액과 단가다. 그날 계획이 없으면 둘 다 0 이다(#837)
public record CandidateBudget(Long campaignId, long reservedAmount, long unitPrice) {
}
