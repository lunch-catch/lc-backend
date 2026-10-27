package com.lunchcatch.adserving.engine.entity;

// 그날 후보의 캠페인 상태다. 캠페인 통보로만 바뀌고 SERVABLE 만 피드에 나간다.
public enum CandidateCampaignState {
    SERVABLE,
    PAUSED,
    ENDED
}
