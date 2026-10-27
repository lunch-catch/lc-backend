package com.lunchcatch.billing.repository;

import com.lunchcatch.billing.dto.BalanceStep;
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

    // 관리자 원장 목록의 한 페이지다
    List<AdminLedgerRow> findAdminLedger(AdminLedgerCondition condition);

    // 기간 안에서 잔액을 바꾸는 줄(DEDUCT 제외)을 기록 시각, ID 오름차순으로 돌려준다
    List<BalanceStep> findBalanceSteps(Long ownerId, LocalDateTime fromInclusive, LocalDateTime toExclusive);

    // 기간 안에 귀속되는 CHARGE, REFUND, ADJUST 를 기록 시각 순으로 돌려준다. ADJUST 는 정정 대상 영업일이 기간 안인 것을 담는다. 점주 한 명의 몇십 건 규모다
    List<FlowRow> findFlowRows(Long ownerId, LocalDateTime fromInclusive, LocalDateTime toExclusive);

    // 영업일 범위의 RESERVE 와 RELEASE 를 영업일과 종류별로 묶는다
    List<BusinessDateSumRow> sumReserveAndReleaseByBusinessDate(Long ownerId, LocalDate from, LocalDate to);

    // 영업일 범위의 DEDUCT 를 판정 기록의 서빙 영업일로 묶는다. 원장 DEDUCT 의 영업일은 NULL 이라 판정 기록과 serve_id 로 잇는다
    List<BusinessDateSumRow> sumDeductByServedBusinessDate(Long ownerId, LocalDate from, LocalDate to);
}
