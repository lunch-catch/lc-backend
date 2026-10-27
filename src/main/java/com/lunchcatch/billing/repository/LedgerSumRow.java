package com.lunchcatch.billing.repository;

import com.lunchcatch.billing.entity.EntryType;

// 점주와 캠페인과 유형별 원장 합이다. positive 는 양수 행의 합, negative 는 음수 행의 합이라 둘이 부호를 나눠 갖는다
public record LedgerSumRow(Long ownerId, Long campaignId, EntryType entryType, Long positive, Long negative) {
}
