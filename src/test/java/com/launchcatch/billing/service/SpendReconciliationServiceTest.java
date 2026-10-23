package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.cache.DailySpend;
import com.launchcatch.billing.cache.DailySpendCounter;
import com.launchcatch.billing.dto.CampaignCounterMismatch;
import com.launchcatch.billing.dto.CampaignOverspend;
import com.launchcatch.billing.dto.CampaignSpend;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
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
    private SpendMismatchRecorder mismatchRecorder;
    @Mock
    private DailySpendCounter spendCounter;
    @Mock
    private BatchExecutionQueryService batchExecutions;

    private SpendReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new SpendReconciliationService(planRepository, ledgerRepository, mismatchRecorder,
                spendCounter, batchExecutions, CLOCK);
    }

    private static BudgetDailyPlan plan(long campaignId) {
        return plan(campaignId, 1000L);
    }

    private static BudgetDailyPlan plan(long campaignId, long reserved) {
        return BudgetDailyPlan.create(campaignId, 7L, PREV, reserved, 10L, false, null);
    }

    private static BatchExecutionRecord run(long runId, String jobName) {
        return new BatchExecutionRecord(runId, jobName, RUN_DATE, "RUNNING", NOW, null, null, "batch-1", 0, null, 1);
    }

    @Test
    void 전일_계획이_없으면_아무것도_읽지_않는다() {
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of());

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isZero();
        verifyNoInteractions(spendCounter, ledgerRepository, mismatchRecorder, batchExecutions);
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
        verify(mismatchRecorder).record(PREV, 42L, NOW, List.of(), List.of(new CampaignCounterMismatch(8L, 400L, 500L)));
    }

    @Test
    void 원장에_소진이_없으면_0과_견주고_작업_행을_찾지_못하면_비워_둔다() {
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of(plan(7L)));
        when(batchExecutions.findByBusinessDate(RUN_DATE)).thenReturn(List.of());
        when(spendCounter.readAll(PREV, List.of(7L))).thenReturn(Map.of(7L, new DailySpend(1000L, 100L)));
        when(ledgerRepository.sumDeductForCampaignsOn(List.of(7L), PREV)).thenReturn(List.of());

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isEqualTo(1);
        verify(mismatchRecorder).record(PREV, null, NOW, List.of(), List.of(new CampaignCounterMismatch(7L, 0L, 100L)));
    }

    @Test
    void 원장이_예약액을_넘으면_카운터가_원장과_같아도_초과_소진으로_기록한다() {
        when(planRepository.findByBusinessDate(PREV))
                .thenReturn(List.of(plan(7L, 1000L), plan(8L, 1000L), plan(9L, 1000L)));
        when(batchExecutions.findByBusinessDate(RUN_DATE)).thenReturn(List.of(run(42L, JOB)));
        // 7 은 해시가 원장으로 재구성돼 카운터도 1200 이다. 8 은 해시가 없다. 9 는 예약액만큼 썼다
        when(spendCounter.readAll(PREV, List.of(7L, 8L, 9L)))
                .thenReturn(Map.of(7L, new DailySpend(1000L, 1200L), 9L, new DailySpend(1000L, 1000L)));
        when(ledgerRepository.sumDeductForCampaignsOn(List.of(7L, 8L, 9L), PREV)).thenReturn(List.of(
                new CampaignSpend(7L, 1200L), new CampaignSpend(8L, 1100L), new CampaignSpend(9L, 1000L)));

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isEqualTo(2);

        // 기대값은 예약액이고 실제값은 원장 DEDUCT 합이다. 카운터 대조로는 7 이 보이지 않고 8 은 견줄 카운터가 없다
        verify(mismatchRecorder).record(PREV, 42L, NOW,
                List.of(new CampaignOverspend(7L, 1000L, 1200L), new CampaignOverspend(8L, 1000L, 1100L)),
                List.of());
    }

    @Test
    void 초과_소진과_카운터_불일치가_함께면_둘_다_기록한다() {
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of(plan(7L, 1000L)));
        when(batchExecutions.findByBusinessDate(RUN_DATE)).thenReturn(List.of(run(42L, JOB)));
        when(spendCounter.readAll(PREV, List.of(7L))).thenReturn(Map.of(7L, new DailySpend(1000L, 990L)));
        when(ledgerRepository.sumDeductForCampaignsOn(List.of(7L), PREV)).thenReturn(List.of(new CampaignSpend(7L, 1010L)));

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isEqualTo(2);
        verify(mismatchRecorder).record(PREV, 42L, NOW,
                List.of(new CampaignOverspend(7L, 1000L, 1010L)),
                List.of(new CampaignCounterMismatch(7L, 1010L, 990L)));
    }

    @Test
    void 캠페인을_500개씩_끊어_읽고_불일치가_없으면_기록을_부르지_않는다() {
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
        verifyNoInteractions(mismatchRecorder);
    }

    @Test
    void 카운터를_읽는_대조에는_트랜잭션을_걸지_않는다() throws Exception {
        // Valkey 응답이 늦어지는 동안 DB 연결과 트랜잭션을 쥐지 않는다. 쓰기는 SpendMismatchRecorder 가 묶음마다 묶는다
        assertThat(SpendReconciliationService.class.getMethod("reconcilePreviousDay", LocalDate.class, String.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(SpendReconciliationService.class.isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    void 해제_뒤_소진과_해제의_합이_예약액을_넘는_캠페인을_이틀_뒤에_초과로_기록한다() {
        LocalDate released = RUN_DATE.minusDays(2);
        when(planRepository.findByBusinessDate(released)).thenReturn(List.of(
                BudgetDailyPlan.create(7L, 7L, released, 1000L, 10L, false, null),
                BudgetDailyPlan.create(8L, 7L, released, 1000L, 10L, false, null),
                BudgetDailyPlan.create(9L, 7L, released, 1000L, 10L, false, null)));
        when(batchExecutions.findByBusinessDate(RUN_DATE)).thenReturn(List.of(run(42L, JOB)));
        // 7 은 소진 400 에 해제 600 이 정확히 예약액이다. 8 은 해제 뒤 소진 100 이 더 들어와 1100 이다. 9 는 해제가 없다
        when(ledgerRepository.sumReleaseForCampaignsOn(List.of(7L, 8L, 9L), released))
                .thenReturn(List.of(new CampaignSpend(7L, 600L), new CampaignSpend(8L, 600L)));
        when(ledgerRepository.sumDeductForCampaignsOn(List.of(7L, 8L, 9L), released)).thenReturn(List.of(
                new CampaignSpend(7L, 400L), new CampaignSpend(8L, 500L), new CampaignSpend(9L, 1200L)));

        assertThat(service.reconcileReleasedDay(RUN_DATE, JOB)).isEqualTo(1);

        // 기대값은 예약액이고 실제값은 소진과 해제의 합이다. 해제 없는 9 는 전일 대사가 본 것이라 여기서 기록하지 않는다
        verify(mismatchRecorder).record(released, 42L, NOW,
                List.of(new CampaignOverspend(8L, 1000L, 1100L)), List.of());
    }

    @Test
    void 이틀_전_계획이_없거나_해제가_없으면_원장_합을_읽지_않는다() {
        LocalDate released = RUN_DATE.minusDays(2);
        when(planRepository.findByBusinessDate(released)).thenReturn(List.of());

        assertThat(service.reconcileReleasedDay(RUN_DATE, JOB)).isZero();
        verifyNoInteractions(ledgerRepository, mismatchRecorder);

        when(planRepository.findByBusinessDate(released)).thenReturn(List.of(
                BudgetDailyPlan.create(7L, 7L, released, 1000L, 10L, false, null)));
        when(batchExecutions.findByBusinessDate(RUN_DATE)).thenReturn(List.of());
        when(ledgerRepository.sumReleaseForCampaignsOn(List.of(7L), released)).thenReturn(List.of());

        assertThat(service.reconcileReleasedDay(RUN_DATE, JOB)).isZero();
        verify(ledgerRepository, never()).sumDeductForCampaignsOn(any(), any());
        verifyNoInteractions(mismatchRecorder);
    }
}
