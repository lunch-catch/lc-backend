package com.launchcatch.adserving.feed.repository;

// 기간 안에 한 업종을 찜한 횟수다.
public record CategoryWishCount(String categoryCode, long count) {
}
