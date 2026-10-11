package com.launchcatch.billing.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// 조건이 동적으로 붙고 떨어지는 원장 조회다
public interface PointLedgerRepositoryCustom {

    // 기록 시각 내림차순, 같으면 ID 내림차순으로 limit 건까지 돌려준다
    List<LedgerRow> findOwnerLedger(OwnerLedgerCondition condition);

    // 영업일 범위의 RESERVE 를 영업일과 캠페인별로 묶는다. campaignId 가 null 이면 전체 캠페인이다
    List<ReserveSumRow> sumReservedByBusinessDate(Long ownerId, LocalDate from, LocalDate to, Long campaignId);

    // 기록 시각이 [from, to) 인 DEDUCT 를 캠페인별로 묶는다. 하루 단위로 호출한다
    List<DeductSumRow> sumDeductedByCampaign(Long ownerId, LocalDateTime from, LocalDateTime to, Long campaignId);
}
