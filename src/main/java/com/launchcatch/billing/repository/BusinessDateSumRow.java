package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.EntryType;
import java.time.LocalDate;

// 영업일별 RESERVE 와 RELEASE 의 합이다. amount 는 부호가 있는 저장값의 합이다
public record BusinessDateSumRow(LocalDate businessDate, EntryType entryType, Long amount) {
}
