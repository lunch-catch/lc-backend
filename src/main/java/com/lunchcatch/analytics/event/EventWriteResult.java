package com.lunchcatch.analytics.event;

public record EventWriteResult(int accepted, int duplicated) {
}
