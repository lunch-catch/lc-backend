package com.lunchcatch.billing.deduction.repository;

import java.time.LocalDate;

// 서빙 영업일별 BILLED 판정의 단가 합이다. amount 는 양수다
public record BusinessDateBilledRow(LocalDate businessDate, Long amount) {
}
