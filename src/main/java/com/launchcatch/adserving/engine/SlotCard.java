package com.launchcatch.adserving.engine;

// 피드에 놓인 카드 한 장이다. 위치는 1부터 센다.
public record SlotCard(NearbyCandidate nearby, SlotType slotType, int slotPosition) {
}
