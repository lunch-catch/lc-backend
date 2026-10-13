package com.launchcatch.adserving.feed;

import com.launchcatch.adserving.feed.entity.SwipeAction;
import java.time.LocalDate;

// swipe_log 한 행에서 피드 배정이 읽는 값이다
public record SwipeHistory(LocalDate businessDate, long campaignId, String categoryCode, SwipeAction action) {
}
