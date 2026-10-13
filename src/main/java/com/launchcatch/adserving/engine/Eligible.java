package com.launchcatch.adserving.engine;

/*
 * 거리 말고 모든 조건을 통과한 후보다.
 * progressRate 는 배분 슬롯의 정렬 키이고, 예산을 읽지 못한 회차에는 0 이다.
 */
public record Eligible(Candidate candidate, int distanceM, double progressRate) {
}
