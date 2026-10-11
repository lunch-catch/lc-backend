package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.EntryType;
import java.time.LocalDateTime;

// 기록 시각의 날짜로 구간에 귀속되는 줄이다. CHARGE, REFUND, ADJUST 만 담긴다
public record FlowRow(EntryType entryType, LocalDateTime createdAt, Long amount) {
}
