package com.launchcatch.adserving.engine;

// 요청 위치에서 거리를 잰 후보다.
public record NearbyCandidate(ServableCandidate candidate, int distanceM) {

    public Long campaignId() {
        return candidate.campaignId();
    }
}
