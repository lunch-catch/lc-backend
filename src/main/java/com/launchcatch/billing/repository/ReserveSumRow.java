package com.launchcatch.billing.repository;

import java.time.LocalDate;

// 영업일과 캠페인별 RESERVE 합이다. amount 는 부호가 있는 저장값의 합이라 음수다
public record ReserveSumRow(LocalDate businessDate, Long campaignId, Long amount) {
}
