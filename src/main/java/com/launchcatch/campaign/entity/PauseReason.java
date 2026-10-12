package com.launchcatch.campaign.entity;

// campaign.paused_reason 컬럼의 값이다. PAUSED일 때만 값이 있고, 그 외에는 NULL이다.
public enum PauseReason {
    ADMIN,
    OWNER,
    NO_POINTS
}
