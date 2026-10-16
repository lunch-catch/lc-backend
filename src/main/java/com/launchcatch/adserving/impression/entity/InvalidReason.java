package com.launchcatch.adserving.impression.entity;

public enum InvalidReason {
    // 카드를 받은 지 30분이 지났거나 서버가 내려주지 않은 카드다
    EXPIRED_OR_UNKNOWN,
    // 다른 사용자가 받은 카드다
    NOT_OWNER
}
