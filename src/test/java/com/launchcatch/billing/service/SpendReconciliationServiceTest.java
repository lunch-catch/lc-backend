package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.cache.DailySpend;
import com.launchcatch.billing.cache.DailySpendCounter;
import com.launchcatch.billing.dto.CampaignSpend;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.billing.repository.SettlementMismatchRepository;
import com.launchcatch.ops.contract.BatchExecutionQueryService;
import com.launchcatch.ops.contract.BatchExecutionRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class SpendReconciliationServiceTest {

    private static final String JOB = "BILLING_SPEND_RECON_PREV";
    private static final LocalDate RUN_DATE = LocalDate.of(2026, 10, 16);
    private static final LocalDate PREV = LocalDate.of(2026, 10, 15);
    // 서버가 UTC 로 떠도 영업일과 기록 시각은 KST 다. 2026-10-15T15:00Z 는 KST 2026-10-16 00:00 이다
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T15:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 0, 0);

    @Mock
    private BudgetDailyPlanRepository planRepository;
    @Mock
    private PointLedgerRepository ledgerRepository;
    @Mock
    private SettlementMismatchRepository mismatchRepository;
    @Mock
    private DailySpendCounter spendCounter;
    @Mock
    private BatchExecutionQueryService batchExecutions;

    private SpendReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new SpendReconciliationService(planRepository, ledgerRepository, mismatchRepository,
                spendCounter, batchExecutions, CLOCK);
    }

    private static BudgetDailyPlan plan(long campaignId) {
        return BudgetDailyPlan.create(campaignId, 7L, PREV, 1000L, 10L, false, null);
    }

    private static BatchExecutionRecord run(long runId, String jobName) {
        return new BatchExecutionRecord(runId, jobName, RUN_DATE, "RUNNING", NOW, null, null, "batch-1", 0, null);
    }

    @Test
    void 전일_계획이_없으면_아무것도_읽지_않는다() {
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of());

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isZero();
        verifyNoInteractions(spendCounter, ledgerRepository, mismatchRepository, batchExecutions);
    }

    @Test
    void 카운터와_원장이_다른_캠페인만_불일치로_기록하고_이_대조를_돌린_작업_행을_가리킨다() {
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of(plan(9L), plan(7L), plan(8L)));
        when(batchExecutions.findByBusinessDate(RUN_DATE))
                .thenReturn(List.of(run(11L, "BILLING_RESERVE"), run(42L, JOB)));
        when(spendCounter.readAll(PREV, List.of(7L, 8L, 9L)))
                .thenReturn(Map.of(7L, new DailySpend(1000L, 300L), 8L, new DailySpend(1000L, 500L)));
        when(ledgerRepository.sumDeductForCampaignsOn(List.of(7L, 8L, 9L), PREV))
                .thenReturn(List.of(new CampaignSpend(7L, 300L), new CampaignSpend(8L, 400L)));

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isEqualTo(1);

        // 기대값은 더 믿는 원장이고 실제값은 카운터다. 해시가 없는 9 는 견줄 값이 없어 기록하지 않는다
        verify(mismatchRepository).insertCampaignSpendMismatchIfAbsent(PREV, 8L, 400L, 500L, 42L, NOW);
        verify(mismatchRepository, never()).insertCampaignSpendMismatchIfAbsent(any(), eq(7L), anyLong(), anyLong(), any(), any());
        verify(mismatchRepository, never()).insertCampaignSpendMismatchIfAbsent(any(), eq(9L), anyLong(), anyLong(), any(), any());
    }

    @Test
    void 원장에_소진이_없으면_0과_견주고_작업_행을_찾지_못하면_비워_둔다() {
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of(plan(7L)));
        when(batchExecutions.findByBusinessDate(RUN_DATE)).thenReturn(List.of());
        when(spendCounter.readAll(PREV, List.of(7L))).thenReturn(Map.of(7L, new DailySpend(1000L, 100L)));
        when(ledgerRepository.sumDeductForCampaignsOn(List.of(7L), PREV)).thenReturn(List.of());

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isEqualTo(1);
        verify(mismatchRepository).insertCampaignSpendMismatchIfAbsent(PREV, 7L, 0L, 100L, null, NOW);
    }

    @Test
    void 캠페인을_500개씩_끊어_읽는다() {
        List<BudgetDailyPlan> plans = new ArrayList<>();
        for (long id = 1; id <= 501; id++) {
            plans.add(plan(id));
        }
        when(planRepository.findByBusinessDate(PREV)).thenReturn(plans);
        when(batchExecutions.findByBusinessDate(RUN_DATE)).thenReturn(List.of(run(42L, JOB)));
        when(spendCounter.readAll(eq(PREV), any())).thenReturn(Map.of());
        when(ledgerRepository.sumDeductForCampaignsOn(any(), eq(PREV))).thenReturn(List.of());

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isZero();
        verify(spendCounter, times(2)).readAll(eq(PREV), any());
        verify(ledgerRepository, times(2)).sumDeductForCampaignsOn(any(), eq(PREV));
        verifyNoInteractions(mismatchRepository);
    }

    @Test
    void 불일치_기록은_트랜잭션_안에서_돈다() throws Exception {
        assertThat(SpendReconciliationService.class.getMethod("reconcilePreviousDay", LocalDate.class, String.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }
}
