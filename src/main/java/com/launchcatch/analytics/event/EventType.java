package com.launchcatch.analytics.event;

public enum EventType {
    IMPRESSION("impression"),
    WISH("wish"),
    PASS("pass"),
    ISSUE_OPEN("issue_open"),
    ISSUE("issue"),
    REDEEM("redeem"),
    EXPIRE("expire"),
    MAP_VIEW("map_view"),
    WISHLIST_VIEW("wishlist_view"),
    NOTIFICATION_SENT("notification_sent"),
    POSTER_OPEN("poster_open");

    private final String value;

    EventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
