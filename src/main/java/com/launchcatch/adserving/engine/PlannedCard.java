package com.launchcatch.adserving.engine;

/*
 * 배정된 카드 한 장이다.
 * slotPosition 은 1부터 센 피드 안의 자리다
 */
public record PlannedCard(FeedCandidate candidate, int distanceM, SlotType slotType, int slotPosition) {
}
