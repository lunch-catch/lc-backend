package com.lunchcatch.billing.dto;

import java.time.LocalDate;

// 계획 스냅샷 캐시의 키다. 계획은 영업일과 캠페인마다 한 행이다
public record PlanSnapshotKey(LocalDate businessDate, Long campaignId) {
}
