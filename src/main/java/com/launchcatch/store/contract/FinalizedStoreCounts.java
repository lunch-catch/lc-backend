package com.launchcatch.store.contract;

// newCount: 그날(KST) 최종 등록한 가게 수, cumulativeCount: 그날 말까지 최종 등록한 가게 수
public record FinalizedStoreCounts(long newCount, long cumulativeCount) { }