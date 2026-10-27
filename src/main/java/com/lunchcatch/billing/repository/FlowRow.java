package com.lunchcatch.billing.repository;

import com.lunchcatch.billing.entity.EntryType;
import java.time.LocalDate;
import java.time.LocalDateTime;

/*
 * 구간에 귀속되는 줄이다. CHARGE, REFUND, ADJUST 만 담긴다.
 * CHARGE 와 REFUND 는 기록 시각의 날짜로, ADJUST 는 adjustTargetDate 로 귀속한다.
 * adjustTargetDate 가 없는 ADJUST(캠페인 없는 조정과 컬럼을 두기 전 행)는 기록 시각의 날짜로 귀속한다.
 */
public record FlowRow(EntryType entryType, LocalDateTime createdAt, Long amount, LocalDate adjustTargetDate) {

    public LocalDate attributedDate() {
        return entryType == EntryType.ADJUST && adjustTargetDate != null ? adjustTargetDate : createdAt.toLocalDate();
    }
}
