package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.cache.DailySpend;
import com.launchcatch.billing.cache.DailySpendCounter;
import com.launchcatch.billing.config.BillingScheduleProperties;
import com.launchcatch.billing.dto.CampaignCounterMismatch;
import com.launchcatch.billing.dto.CampaignOverspend;
import com.launchcatch.billing.dto.CampaignSpend;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.ops.contract.BatchExecutionQueryService;
import com.launchcatch.ops.contract.BatchExecutionRecord;
import java.time.Clock;
import java.time.Duration;
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

    private static final BillingScheduleProperties SCHEDULE =
            new BillingScheduleProperties("10:00", "12:59", Duration.ofMinutes(30), List.of(34, 33, 33));

    @Mock
    private BudgetDailyPlanRepository planRepository;
    @Mock
    private PointLedgerRepository ledgerRepository;
    @Mock
    private SpendMismatchRecorder mismatchRecorder;
    @Mock
    private DailySpendCounter spendCounter;
    @Mock
    private SpendHashRebuildService hashRebuild;
    @Mock
    private BatchExecutionQueryService batchExecutions;

    private SpendReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new SpendReconciliationService(planRepository, ledgerRepository, mismatchRecorder,
                spendCounter, hashRebuild, batchExecutions, SCHEDULE, CLOCK);
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
    void 서빙_시간대나_노출_인정_시간_안이면_당일_대조를_거부한다() {
        Clock midServing = Clock.fixed(Instant.parse("2026-10-16T02:00:00Z"), ZoneId.of("Asia/Seoul")); // KST 11:00
        SpendReconciliationService midServingService = new SpendReconciliationService(planRepository, ledgerRepository,
                mismatchRecorder, spendCounter, hashRebuild, batchExecutions, SCHEDULE, midServing);

        assertThatThrownBy(() -> midServingService.reconcileToday(RUN_DATE, JOB)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(planRepository, ledgerRepository, spendCounter, hashRebuild, mismatchRecorder);
    }

    @Test
    void 노출_인정_시간이_끝난_뒤면_당일_대조를_허용한다() {
        Clock atReleaseTime = Clock.fixed(Instant.parse("2026-10-16T04:30:00Z"), ZoneId.of("Asia/Seoul")); // KST 13:30
        SpendReconciliationService releaseService = new SpendReconciliationService(planRepository, ledgerRepository,
                mismatchRecorder, spendCounter, hashRebuild, batchExecutions, SCHEDULE, atReleaseTime);
        when(planRepository.findByBusinessDate(RUN_DATE)).thenReturn(List.of());

        assertThat(releaseService.reconcileToday(RUN_DATE, JOB)).isZero();
    }

    @Test
    void 그날_계획이_없으면_아무것도_읽지_않는다() {
        when(planRepository.findByBusinessDate(RUN_DATE)).thenReturn(List.of());

        assertThat(service.reconcileToday(RUN_DATE, JOB)).isZero();
        verifyNoInteractions(spendCounter, ledgerRepository, mismatchRecorder, hashRebuild, batchExecutions);
    }

    @Test
    void 카운터가_원장과_다르면_지우고_다시_만들며_CACHE_REBUILD로_기록한다() {
        when(planRepository.findByBusinessDate(RUN_DATE)).thenReturn(List.of(plan(7L), plan(8L)));
        when(batchExecutions.findByBusinessDate(RUN_DATE)).thenReturn(List.of(run(42L, JOB)));
        when(spendCounter.readAll(RUN_DATE, List.of(7L, 8L)))
                .thenReturn(Map.of(7L, new DailySpend(1000L, 1200L), 8L, new DailySpend(1000L, 400L)));
        when(ledgerRepository.sumDeductForCampaignsOn(List.of(7L, 8L), RUN_DATE))
                .thenReturn(List.of(new CampaignSpend(7L, 1000L), new CampaignSpend(8L, 400L)));

        assertThat(service.reconcileToday(RUN_DATE, JOB)).isEqualTo(1);

        // 8 은 카운터와 원장이 같아 손대지 않는다. 7 만 카운터(1200)가 원장(1000)과 달라 다시 만든다
        verify(spendCounter).evict(RUN_DATE, 7L);
        verify(hashRebuild).rebuildIfAbsent(RUN_DATE, 7L);
        verify(spendCounter, never()).evict(RUN_DATE, 8L);
        verify(hashRebuild, never()).rebuildIfAbsent(RUN_DATE, 8L);
        verify(mismatchRecorder).recordToday(RUN_DATE, 42L, NOW, List.of(), List.of(new CampaignCounterMismatch(7L, 1000L, 1200L)));
    }

    @Test
    void 해시가_없으면_다시_만들지_않고_건너뛴다() {
        when(planRepository.findByBusinessDate(RUN_DATE)).thenReturn(List.of(plan(7L)));
        when(batchExecutions.findByBusinessDate(RUN_DATE)).thenReturn(List.of(run(42L, JOB)));
        when(spendCounter.readAll(RUN_DATE, List.of(7L))).thenReturn(Map.of());
        when(ledgerRepository.sumDeductForCampaignsOn(List.of(7L), RUN_DATE)).thenReturn(List.of(new CampaignSpend(7L, 500L)));

        assertThat(service.reconcileToday(RUN_DATE, JOB)).isZero();
        verifyNoInteractions(hashRebuild);
        verify(spendCounter, never()).evict(any(), any());
        verifyNoInteractions(mismatchRecorder);
    }

    @Test
    void 원장이_예약액을_넘으면_카운터가_같아도_초과_소진으로_기록하고_다시_만들지_않는다() {
        when(planRepository.findByBusinessDate(RUN_DATE)).thenReturn(List.of(plan(7L, 1000L)));
        when(batchExecutions.findByBusinessDate(RUN_DATE)).thenReturn(List.of(run(42L, JOB)));
        when(spendCounter.readAll(RUN_DATE, List.of(7L))).thenReturn(Map.of(7L, new DailySpend(1000L, 1200L)));
        when(ledgerRepository.sumDeductForCampaignsOn(List.of(7L), RUN_DATE)).thenReturn(List.of(new CampaignSpend(7L, 1200L)));

        assertThat(service.reconcileToday(RUN_DATE, JOB)).isEqualTo(1);

        verify(mismatchRecorder).recordToday(RUN_DATE, 42L, NOW, List.of(new CampaignOverspend(7L, 1000L, 1200L)), List.of());
        verifyNoInteractions(hashRebuild);
    }

    @Test
    void 당일_대조에도_트랜잭션을_걸지_않는다() throws Exception {
        assertThat(SpendReconciliationService.class.getMethod("reconcileToday", LocalDate.class, String.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
    }
}
