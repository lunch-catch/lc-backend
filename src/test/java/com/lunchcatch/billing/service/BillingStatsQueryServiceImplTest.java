package com.lunchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.contract.CampaignBillingCount;
import com.lunchcatch.billing.contract.CampaignDailyBudget;
import com.lunchcatch.billing.contract.DailyLedgerTotals;
import com.lunchcatch.billing.contract.DeductionStatus;
import com.lunchcatch.billing.contract.OwnerCampaignLedgerSum;
import com.lunchcatch.billing.entity.BudgetDailyPlan;
import com.lunchcatch.billing.entity.EntryType;
import com.lunchcatch.billing.repository.BudgetDailyPlanRepository;
import com.lunchcatch.billing.repository.DeductionCountRow;
import com.lunchcatch.billing.repository.DeductionResultRepository;
import com.lunchcatch.billing.repository.LedgerSumRow;
import com.lunchcatch.billing.repository.PointLedgerRepository;
import com.lunchcatch.billing.repository.SettlementMismatchRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BillingStatsQueryServiceImplTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 15);
    private static final LocalDateTime FROM = DAY.atStartOfDay();
    private static final LocalDateTime TO = DAY.plusDays(1).atStartOfDay();

    @Mock PointLedgerRepository ledgerRepository;
    @Mock DeductionResultRepository deductionRepository;
    @Mock BudgetDailyPlanRepository planRepository;
    @Mock SettlementMismatchRepository mismatchRepository;
    @InjectMocks BillingStatsQueryServiceImpl service;

    @Test
    void 세_출처의_합계를_점주와_캠페인으로_합쳐_유형별로_나눈다() {
        when(ledgerRepository.sumReserveReleaseOn(DAY)).thenReturn(List.of(
                new LedgerSumRow(1L, 10L, EntryType.RESERVE, 0L, -5000L),
                new LedgerSumRow(1L, 10L, EntryType.RELEASE, 2000L, 0L)));
        when(ledgerRepository.sumDeductOn(DAY)).thenReturn(List.of(
                new LedgerSumRow(1L, 10L, EntryType.DEDUCT, 0L, -3000L)));
        when(ledgerRepository.sumChargeRefundBetween(FROM, TO)).thenReturn(List.of(
                new LedgerSumRow(1L, null, EntryType.CHARGE, 10000L, 0L),
                new LedgerSumRow(2L, null, EntryType.REFUND, 0L, -700L)));
        when(ledgerRepository.sumAdjustOn(DAY, FROM, TO)).thenReturn(List.of(
                new LedgerSumRow(1L, 10L, EntryType.ADJUST, 300L, -50L)));

        List<OwnerCampaignLedgerSum> sums = service.ledgerSums(DAY);

        assertThat(sums).containsExactlyInAnyOrder(
                new OwnerCampaignLedgerSum(1L, 10L, 0, -5000, -3000, 2000, 0, 300, -50),
                new OwnerCampaignLedgerSum(1L, null, 10000, 0, 0, 0, 0, 0, 0),
                new OwnerCampaignLedgerSum(2L, null, 0, 0, 0, 0, -700, 0, 0));
    }

    @Test
    void 정정_대상_영업일은_그날_기록된_양수_정정이_가리키는_날짜를_그대로_돌려준다() {
        when(ledgerRepository.findCorrectionTargetDates(FROM, TO))
                .thenReturn(List.of(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 13)));

        assertThat(service.correctionTargetDates(DAY))
                .containsExactly(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 13));
    }

    @Test
    void 그날_원장이_없으면_빈_목록이다() {
        assertThat(service.ledgerSums(DAY)).isEmpty();
    }

    @Test
    void 과금_상태별_건수를_캠페인마다_모으고_없는_상태는_0이다() {
        when(deductionRepository.countByStatusOn(DAY)).thenReturn(List.of(
                new DeductionCountRow(10L, DeductionStatus.BILLED, 7L),
                new DeductionCountRow(10L, DeductionStatus.NOT_BILLED_PAUSED, 2L),
                new DeductionCountRow(11L, DeductionStatus.NOT_BILLED_BUDGET, 4L),
                new DeductionCountRow(11L, DeductionStatus.NOT_BILLED_NO_PLAN, 3L),
                new DeductionCountRow(11L, DeductionStatus.NOT_BILLED_CLOSED, 5L)));

        assertThat(service.billingCounts(DAY)).containsExactlyInAnyOrder(
                new CampaignBillingCount(10L, 7, 0, 2, 0),
                new CampaignBillingCount(11L, 0, 4, 0, 3, 5));
    }

    @Test
    void 하루_예산과_단가를_계획에서_옮긴다() {
        when(planRepository.findByBusinessDate(DAY)).thenReturn(List.of(
                BudgetDailyPlan.create(10L, 1L, DAY, 5000L, 100L, false, null)));

        assertThat(service.dailyBudgets(DAY)).containsExactly(new CampaignDailyBudget(10L, 5000, 100));
    }

    @Test
    void 날짜_말_잔액과_그날_합계와_불일치_건수를_묶는다() {
        when(ledgerRepository.sumUnspentBefore(TO)).thenReturn(42000L);
        when(ledgerRepository.sumUnspentBetween(FROM, TO)).thenReturn(8000L);
        when(mismatchRepository.countByBusinessDate(DAY)).thenReturn(3L);

        assertThat(service.dailyTotals(DAY)).isEqualTo(new DailyLedgerTotals(42000, 8000, 3));
    }
}
