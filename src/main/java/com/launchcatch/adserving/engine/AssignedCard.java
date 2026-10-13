package com.launchcatch.adserving.engine;

/*
 * 피드의 한 자리에 놓인 후보다.
 * slotPosition 은 1 부터 빈 자리 없이 이어진다.
 */
public record AssignedCard(Candidate candidate, int distanceM, SlotType slotType, int slotPosition) {
}
