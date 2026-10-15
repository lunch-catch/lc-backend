package com.launchcatch.billing.cache;

/**
 * 당일 소진 해시에서 읽은 한 캠페인의 예약액과 소진액이다.
 *
 * @param reserved 그날 예약액
 * @param spend    지금까지 과금한 금액
 */
public record DailySpend(long reserved, long spend) {
}
