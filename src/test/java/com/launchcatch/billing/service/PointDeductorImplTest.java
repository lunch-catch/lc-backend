package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.contract.DeductionOutcome;
import com.launchcatch.billing.contract.DeductionRequest;
import com.launchcatch.billing.contract.DeductionStatus;
import com.launchcatch.billing.contract.PointDeductor;
import com.launchcatch.billing.dto.PlanSnapshot;
import com.launchcatch.billing.cache.DailySpendCounter;
import com.launchcatch.billing.cache.InMemoryDailySpendCounter;
import com.launchcatch.billing.cache.DeductOutcome;
import com.launchcatch.billing.repository.DeductionResultRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.DeductionBatchRepository;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.ops.contract.PlatformSettingQueryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class PointDeductorImplTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final UUID SERVE_ID = UUID.fromString("0192f3a4-7b5c-7d1e-9a2b-3c4d5e6f7a8b");
    private static final Long CAMPAIGN_ID = 7L;
    private static final PlanSnapshot PLAN = new PlanSnapshot(3L, 10L);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T02:30:00Z"), ClockConfig.ZONE);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 11, 30);

    @Mock
    private DailyPlanSnapshotService snapshotService;
    @Mock
    private SpendHashRebuildService rebuildService;
    @Mock
    private DailySpendCounter spendCounter;
    @Mock
    private DeductionResultRepository deductionResultRepository;
    @Mock
    private PointLedgerRepository ledgerRepository;
    @Mock
    private CampaignQueryService campaignQuery;
    @Mock
    private PlatformSettingQueryService platformSetting;
    @Mock
    private BudgetDailyPlanRepository planRepository;
    @Mock
    private DeductionBatchRepository batchRepository;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final List<String> finishedTransactions = new ArrayList<>();
    /*
     * 묶음 차감의 점주별 트랜잭션을 흉내 내고 끝난 결과를 순서대로 남긴다.
     * 동기화의 시작, 알림, 정리는 상위 클래스가 실제 트랜잭션과 같은 방식으로 한다.
     */
    private final TransactionTemplate transactions = new TransactionTemplate(new AbstractPlatformTransactionManager() {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            finishedTransactions.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            finishedTransactions.add("rollback");
        }
    });
    private PointDeductorImpl deductor;

    @BeforeEach
    void setUp() {
        // 운영에서는 REQUIRED 트랜잭션이 동기화를 켜 둔다. 시험도 같은 상태로 만든다
        TransactionSynchronizationManager.initSynchronization();
        deductor = new PointDeductorImpl(snapshotService, rebuildService, spendCounter,
                deductionResultRepository, ledgerRepository, campaignQuery, platformSetting, CLOCK, meterRegistry,
                planRepository, batchRepository, transactions);
        lenient().when(snapshotService.find(DAY, CAMPAIGN_ID)).thenReturn(Optional.of(PLAN));
        lenient().when(planRepository.findForShare(CAMPAIGN_ID, DAY)).thenReturn(Optional.of(plan(DAY, null)));
        lenient().when(planRepository.findForShare(CAMPAIGN_ID, DAY.minusDays(1)))
                .thenReturn(Optional.of(plan(DAY.minusDays(1), null)));
        // 기본은 새로 기록된 경우다. 중복은 해당 시험에서 0 을 돌려주게 한다
        lenient().when(deductionResultRepository.insertIfAbsent(any(UUID.class), any(), any(), any(),
                any(DeductionStatus.class), anyLong(), any())).thenReturn(1);
    }

    // 묶음 차감 시험은 동기화를 끈 채 끝나므로 켜져 있을 때만 정리한다
    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // 등록된 동기화에 트랜잭션이 이 결과로 끝났다고 알린다
    private static void complete(int completionStatus) {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(completionStatus);
        }
    }

    private static CampaignInfo campaign(long campaignId, long ownerId) {
        return new CampaignInfo(campaignId, ownerId, 100L, CampaignStatus.ENDED, 100, null, null, null, null, null,
                null, 0, null, 0, null, List.of(), null, null, null, null, 1000L);
    }

    // 그날 계획 행이다. releasedAt 이 있으면 해제가 끝난 계획이다(#1049)
    private static BudgetDailyPlan plan(LocalDate businessDate, LocalDateTime releasedAt) {
        BudgetDailyPlan plan = BudgetDailyPlan.create(CAMPAIGN_ID, 3L, businessDate, 1000L, 10L, false, null);
        ReflectionTestUtils.setField(plan, "releasedAt", releasedAt);
        return plan;
    }

    private double undoCount(String result) {
        return meterRegistry.counter(PointDeductorImpl.UNDO_METRIC, "result", result).count();
    }

    @Test
    void 과금하면_판정을_기록하고_단가의_음수로_원장에_남긴다() {
        when(spendCounter.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L)).thenReturn(DeductOutcome.of(DeductionStatus.BILLED));

        DeductionStatus status = deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        assertThat(status).isEqualTo(DeductionStatus.BILLED);
        verify(deductionResultRepository).insertIfAbsent(SERVE_ID, CAMPAIGN_ID, 3L, DAY, DeductionStatus.BILLED, 10L, NOW);
        verify(ledgerRepository).insertDeductIfAbsent(3L, CAMPAIGN_ID, SERVE_ID, -10L, NOW);
        verify(rebuildService, never()).rebuildIfAbsent(any(), any());
    }

    @Test
    void 예약액에_닿으면_판정만_기록하고_원장은_쓰지_않는다() {
        when(spendCounter.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L)).thenReturn(DeductOutcome.of(DeductionStatus.NOT_BILLED_BUDGET));

        DeductionStatus status = deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        assertThat(status).isEqualTo(DeductionStatus.NOT_BILLED_BUDGET);
        verify(deductionResultRepository).insertIfAbsent(SERVE_ID, CAMPAIGN_ID, 3L, DAY, DeductionStatus.NOT_BILLED_BUDGET, 10L, NOW);
        verify(ledgerRepository, never()).insertDeductIfAbsent(any(), any(), any(UUID.class), anyLong(), any());
    }

    @Test
    void 중단된_캠페인은_판정만_기록하고_원장은_쓰지_않는다() {
        when(spendCounter.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L)).thenReturn(DeductOutcome.of(DeductionStatus.NOT_BILLED_PAUSED));

        DeductionStatus status = deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        assertThat(status).isEqualTo(DeductionStatus.NOT_BILLED_PAUSED);
        verify(deductionResultRepository).insertIfAbsent(SERVE_ID, CAMPAIGN_ID, 3L, DAY, DeductionStatus.NOT_BILLED_PAUSED, 10L, NOW);
        verify(ledgerRepository, never()).insertDeductIfAbsent(any(), any(), any(UUID.class), anyLong(), any());
    }

    @Test
    void 같은_serveId를_다시_부르면_첫_판정을_돌려주고_INSERT는_무변경이다() {
        when(spendCounter.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L)).thenReturn(DeductOutcome.of(DeductionStatus.BILLED));
        when(deductionResultRepository.insertIfAbsent(SERVE_ID, CAMPAIGN_ID, 3L, DAY, DeductionStatus.BILLED, 10L, NOW))
                .thenReturn(1, 2);
        when(deductionResultRepository.findFirstStatus(SERVE_ID)).thenReturn(Optional.of(DeductionStatus.BILLED));

        DeductionStatus first = deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);
        DeductionStatus second = deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        assertThat(first).isEqualTo(DeductionStatus.BILLED);
        assertThat(second).isEqualTo(first);
        verify(ledgerRepository, times(1)).insertDeductIfAbsent(3L, CAMPAIGN_ID, SERVE_ID, -10L, NOW);
    }

    @Test
    void 처리_표시가_사라져_다시_판정해도_저장된_처음_판정을_돌려주고_원장은_쓰지_않는다() {
        when(spendCounter.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L)).thenReturn(DeductOutcome.of(DeductionStatus.BILLED));
        when(deductionResultRepository.insertIfAbsent(SERVE_ID, CAMPAIGN_ID, 3L, DAY, DeductionStatus.BILLED, 10L, NOW))
                .thenReturn(2);
        when(deductionResultRepository.findFirstStatus(SERVE_ID))
                .thenReturn(Optional.of(DeductionStatus.NOT_BILLED_BUDGET));

        DeductionStatus status = deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        assertThat(status).isEqualTo(DeductionStatus.NOT_BILLED_BUDGET);
        verify(ledgerRepository, never()).insertDeductIfAbsent(any(), any(), any(UUID.class), anyLong(), any());
    }

    @Test
    void 그날_계획이_없으면_캠페인의_점주와_운영_단가로_계획_없음을_기록하고_Valkey와_원장은_건드리지_않는다() {
        when(snapshotService.find(DAY, CAMPAIGN_ID)).thenReturn(Optional.empty());
        when(campaignQuery.findAllById(List.of(CAMPAIGN_ID))).thenReturn(List.of(campaign(CAMPAIGN_ID, 5L)));
        when(platformSetting.getImpressionUnitPrice()).thenReturn(12);

        DeductionStatus status = deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        assertThat(status).isEqualTo(DeductionStatus.NOT_BILLED_NO_PLAN);
        verify(deductionResultRepository).insertIfAbsent(SERVE_ID, CAMPAIGN_ID, 5L, DAY, DeductionStatus.NOT_BILLED_NO_PLAN, 12L, NOW);
        verifyNoInteractions(spendCounter, rebuildService, ledgerRepository);
        assertThat(meterRegistry.counter(PointDeductorImpl.NO_PLAN_METRIC).count()).isEqualTo(1.0);
    }

    @Test
    void 계획_없음을_다시_부르면_첫_판정을_돌려준다() {
        when(snapshotService.find(DAY, CAMPAIGN_ID)).thenReturn(Optional.empty());
        when(campaignQuery.findAllById(List.of(CAMPAIGN_ID))).thenReturn(List.of(campaign(CAMPAIGN_ID, 5L)));
        when(platformSetting.getImpressionUnitPrice()).thenReturn(12);
        when(deductionResultRepository.insertIfAbsent(SERVE_ID, CAMPAIGN_ID, 5L, DAY, DeductionStatus.NOT_BILLED_NO_PLAN, 12L, NOW))
                .thenReturn(2);
        when(deductionResultRepository.findFirstStatus(SERVE_ID)).thenReturn(Optional.of(DeductionStatus.NOT_BILLED_NO_PLAN));

        assertThat(deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY)).isEqualTo(DeductionStatus.NOT_BILLED_NO_PLAN);
    }

    @Test
    void 계획도_캠페인도_없으면_예외다() {
        when(snapshotService.find(DAY, CAMPAIGN_ID)).thenReturn(Optional.empty());
        when(campaignQuery.findAllById(List.of(CAMPAIGN_ID))).thenReturn(List.of());

        assertThatThrownBy(() -> deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("campaignId=7");
        verify(deductionResultRepository, never()).insertIfAbsent(any(UUID.class), any(), any(), any(), any(DeductionStatus.class), anyLong(), any());
    }

    @Test
    void 중복인데_저장된_판정을_읽을_수_없으면_예외다() {
        when(spendCounter.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L)).thenReturn(DeductOutcome.of(DeductionStatus.BILLED));
        when(deductionResultRepository.insertIfAbsent(SERVE_ID, CAMPAIGN_ID, 3L, DAY, DeductionStatus.BILLED, 10L, NOW))
                .thenReturn(2);
        when(deductionResultRepository.findFirstStatus(SERVE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serveId=");
    }

    @Test
    void 해시가_없으면_재구성_뒤_한_번만_다시_판정한다() {
        when(spendCounter.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L))
                .thenReturn(DeductOutcome.hashMissing(), DeductOutcome.of(DeductionStatus.BILLED));

        DeductionStatus status = deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        assertThat(status).isEqualTo(DeductionStatus.BILLED);
        verify(rebuildService).rebuildIfAbsent(DAY, CAMPAIGN_ID);
        verify(spendCounter, times(2)).deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L);
    }

    @Test
    void 재구성한_뒤에도_해시가_없으면_예외이고_아무것도_기록하지_않는다() {
        when(spendCounter.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L)).thenReturn(DeductOutcome.hashMissing());

        assertThatThrownBy(() -> deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("campaignId=7");
        verify(spendCounter, times(2)).deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L);
        verify(deductionResultRepository, never()).insertIfAbsent(any(UUID.class), any(), any(), any(), any(DeductionStatus.class), anyLong(), any());
    }

    @Test
    void 트랜잭션이_없으면_새로_시작하고_있으면_참여한다() throws NoSuchMethodException {
        Transactional transactional = PointDeductorImpl.class
                .getMethod("deduct", UUID.class, Long.class, LocalDate.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
    }

    // 마감은 날짜가 아니라 그 계획의 해제 여부로 본다(#1049)
    @Test
    void 해제가_끝난_계획의_노출은_미과금으로_남기고_카운터와_원장은_건드리지_않는다() {
        LocalDate yesterday = DAY.minusDays(1);
        when(snapshotService.find(yesterday, CAMPAIGN_ID)).thenReturn(Optional.of(PLAN));
        when(planRepository.findForShare(CAMPAIGN_ID, yesterday)).thenReturn(Optional.of(plan(yesterday, NOW.minusHours(11))));

        DeductionStatus status = deductor.deduct(SERVE_ID, CAMPAIGN_ID, yesterday);

        assertThat(status).isEqualTo(DeductionStatus.NOT_BILLED_CLOSED);
        verify(deductionResultRepository).insertIfAbsent(SERVE_ID, CAMPAIGN_ID, 3L, yesterday,
                DeductionStatus.NOT_BILLED_CLOSED, 10L, NOW);
        verifyNoInteractions(spendCounter, rebuildService, ledgerRepository);
    }

    // 자정 직전에 받은 노출을 릴레이가 자정 뒤에 넘겨도 해제 전이면 그날 예산으로 과금한다
    @Test
    void 해제_전이면_자정이_지나도_전날_노출을_그날_예산으로_과금한다() {
        LocalDate yesterday = DAY.minusDays(1);
        when(snapshotService.find(yesterday, CAMPAIGN_ID)).thenReturn(Optional.of(PLAN));
        when(spendCounter.deduct(yesterday, CAMPAIGN_ID, SERVE_ID, 10L)).thenReturn(DeductOutcome.of(DeductionStatus.BILLED));

        assertThat(deductor.deduct(SERVE_ID, CAMPAIGN_ID, yesterday)).isEqualTo(DeductionStatus.BILLED);
        verify(ledgerRepository).insertDeductIfAbsent(3L, CAMPAIGN_ID, SERVE_ID, -10L, NOW);
    }

    // 해제는 다음 날 00:00 에 끝나고 릴레이는 어제 이후 노출만 넘긴다. 그보다 앞선 영업일은 계획을 잠그지 않고 마감으로 본다
    @Test
    void 어제보다_앞선_영업일은_계획을_잠그지_않고_마감으로_본다() {
        LocalDate twoDaysAgo = DAY.minusDays(2);
        when(snapshotService.find(twoDaysAgo, CAMPAIGN_ID)).thenReturn(Optional.of(PLAN));

        assertThat(deductor.deduct(SERVE_ID, CAMPAIGN_ID, twoDaysAgo)).isEqualTo(DeductionStatus.NOT_BILLED_CLOSED);
        verify(planRepository, never()).findForShare(CAMPAIGN_ID, twoDaysAgo);
        verifyNoInteractions(spendCounter, rebuildService, ledgerRepository);
    }

    @Test
    void 해제_전이면_그날_예산으로_과금한다() {
        when(spendCounter.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L))
                .thenReturn(DeductOutcome.of(DeductionStatus.BILLED));

        assertThat(deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY)).isEqualTo(DeductionStatus.BILLED);
    }

    // 계획 행을 잠가 읽으므로 해제가 커밋되기를 기다린 뒤 해제 시각을 본다
    @Test
    void 계획_잠금을_기다리는_동안_해제가_끝나면_과금하지_않는다() {
        when(planRepository.findForShare(CAMPAIGN_ID, DAY)).thenReturn(Optional.of(plan(DAY, NOW)));

        assertThat(deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY)).isEqualTo(DeductionStatus.NOT_BILLED_CLOSED);
        verifyNoInteractions(spendCounter, rebuildService, ledgerRepository);
    }

    @Test
    void 마감_뒤_재호출은_이미_커밋된_과금_판정을_그대로_돌려준다() {
        LocalDate yesterday = DAY.minusDays(1);
        when(snapshotService.find(yesterday, CAMPAIGN_ID)).thenReturn(Optional.of(PLAN));
        when(planRepository.findForShare(CAMPAIGN_ID, yesterday)).thenReturn(Optional.of(plan(yesterday, NOW.minusHours(11))));
        when(deductionResultRepository.insertIfAbsent(SERVE_ID, CAMPAIGN_ID, 3L, yesterday,
                DeductionStatus.NOT_BILLED_CLOSED, 10L, NOW)).thenReturn(2);
        when(deductionResultRepository.findFirstStatus(SERVE_ID)).thenReturn(Optional.of(DeductionStatus.BILLED));

        assertThat(deductor.deduct(SERVE_ID, CAMPAIGN_ID, yesterday)).isEqualTo(DeductionStatus.BILLED);
        verifyNoInteractions(spendCounter, rebuildService, ledgerRepository);
    }

    @Test
    void 마감된_날의_계획이_없어도_원래_영업일로_마감_미과금을_남긴다() {
        LocalDate yesterday = DAY.minusDays(1);
        when(snapshotService.find(yesterday, CAMPAIGN_ID)).thenReturn(Optional.empty());
        when(campaignQuery.findAllById(List.of(CAMPAIGN_ID))).thenReturn(List.of(campaign(CAMPAIGN_ID, 5L)));
        when(platformSetting.getImpressionUnitPrice()).thenReturn(12);

        assertThat(deductor.deduct(SERVE_ID, CAMPAIGN_ID, yesterday)).isEqualTo(DeductionStatus.NOT_BILLED_CLOSED);
        verify(deductionResultRepository).insertIfAbsent(SERVE_ID, CAMPAIGN_ID, 5L, yesterday,
                DeductionStatus.NOT_BILLED_CLOSED, 12L, NOW);
        verifyNoInteractions(spendCounter, rebuildService, ledgerRepository);
    }

    @Test
    void 계획_스냅샷은_있는데_계획_행을_잠가_읽지_못하면_과금하지_않고_실패한다() {
        when(planRepository.findForShare(CAMPAIGN_ID, DAY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("campaignId=7");
        verifyNoInteractions(spendCounter, rebuildService, ledgerRepository, deductionResultRepository);
    }

    private PointDeductorImpl deductorAt(Clock time) {
        return new PointDeductorImpl(snapshotService, rebuildService, spendCounter, deductionResultRepository,
                ledgerRepository, campaignQuery, platformSetting, time, meterRegistry, planRepository, batchRepository, transactions);
    }

    @Test
    void 새로_판정한_노출은_트랜잭션이_롤백되면_되돌린다() {
        when(spendCounter.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L)).thenReturn(DeductOutcome.of(DeductionStatus.BILLED));
        deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(spendCounter).undo(DAY, CAMPAIGN_ID, SERVE_ID, 10L);
        assertThat(undoCount("ok")).isEqualTo(1.0);
    }

    @Test
    void 커밋되었거나_결과를_알_수_없으면_되돌리지_않는다() {
        when(spendCounter.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L)).thenReturn(DeductOutcome.of(DeductionStatus.BILLED));
        deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        complete(TransactionSynchronization.STATUS_COMMITTED);
        complete(TransactionSynchronization.STATUS_UNKNOWN);

        verify(spendCounter, never()).undo(any(), any(), any(UUID.class), anyLong());
        assertThat(undoCount("ok")).isZero();
    }

    @Test
    void 이미_처리된_노출을_다시_받은_호출은_롤백돼도_되돌리지_않는다() {
        when(spendCounter.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L)).thenReturn(DeductOutcome.replay(DeductionStatus.BILLED));
        when(deductionResultRepository.insertIfAbsent(SERVE_ID, CAMPAIGN_ID, 3L, DAY, DeductionStatus.BILLED, 10L, NOW))
                .thenReturn(2);
        when(deductionResultRepository.findFirstStatus(SERVE_ID)).thenReturn(Optional.of(DeductionStatus.BILLED));
        deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(spendCounter, never()).undo(any(), any(), any(UUID.class), anyLong());
    }

    @Test
    void 해시가_없어_재구성한_뒤_판정한_노출도_롤백되면_한_번만_되돌린다() {
        when(spendCounter.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L))
                .thenReturn(DeductOutcome.hashMissing(), DeductOutcome.of(DeductionStatus.BILLED));
        deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(spendCounter, times(1)).undo(DAY, CAMPAIGN_ID, SERVE_ID, 10L);
    }

    @Test
    void 되돌리기가_실패해도_예외를_올리지_않고_실패_지표만_올린다() {
        when(spendCounter.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L)).thenReturn(DeductOutcome.of(DeductionStatus.BILLED));
        doThrow(new IllegalStateException("valkey down")).when(spendCounter).undo(DAY, CAMPAIGN_ID, SERVE_ID, 10L);
        deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(undoCount("failed")).isEqualTo(1.0);
        assertThat(undoCount("ok")).isZero();
    }

    @Test
    void 롤백된_노출은_Fake에서도_소진과_처리_표시가_함께_돌아가고_재호출이_다시_과금한다() {
        InMemoryDailySpendCounter fake = new InMemoryDailySpendCounter();
        fake.seed(DAY, CAMPAIGN_ID, 100L, 0L, false);
        PointDeductorImpl real = deductorWith(fake);

        assertThat(real.deduct(SERVE_ID, CAMPAIGN_ID, DAY)).isEqualTo(DeductionStatus.BILLED);
        assertThat(fake.spendOf(DAY, CAMPAIGN_ID)).isEqualTo(10L);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(fake.spendOf(DAY, CAMPAIGN_ID)).isZero();

        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        assertThat(real.deduct(SERVE_ID, CAMPAIGN_ID, DAY)).isEqualTo(DeductionStatus.BILLED);
        assertThat(fake.spendOf(DAY, CAMPAIGN_ID)).isEqualTo(10L);
    }

    // 아래 시험은 Mockito 대신 Lua 와 같은 규칙의 Fake 를 연결해 판정이 쌓이는 모습을 그대로 본다
    private PointDeductorImpl deductorWith(InMemoryDailySpendCounter fake) {
        return new PointDeductorImpl(snapshotService, rebuildService, fake, deductionResultRepository, ledgerRepository,
                campaignQuery, platformSetting, CLOCK,
                meterRegistry, planRepository, batchRepository, transactions);
    }

    @Test
    void 단가_10_예약_100이면_열_건은_과금하고_열한_번째는_예약액에_닿는다() {
        InMemoryDailySpendCounter fake = new InMemoryDailySpendCounter();
        fake.seed(DAY, CAMPAIGN_ID, 100L, 0L, false);
        PointDeductorImpl real = deductorWith(fake);

        for (int i = 0; i < 10; i++) {
            assertThat(real.deduct(UUID.randomUUID(), CAMPAIGN_ID, DAY)).isEqualTo(DeductionStatus.BILLED);
        }
        DeductionStatus eleventh = real.deduct(UUID.randomUUID(), CAMPAIGN_ID, DAY);

        assertThat(eleventh).isEqualTo(DeductionStatus.NOT_BILLED_BUDGET);
        verify(ledgerRepository, times(10)).insertDeductIfAbsent(any(), any(), any(UUID.class), anyLong(), any());
        verify(deductionResultRepository, times(11))
                .insertIfAbsent(any(UUID.class), any(), any(), any(), any(DeductionStatus.class), anyLong(), any());
    }

    @Test
    void 같은_serveId를_두_번_불러도_소진은_한_번만_늘어난다() {
        InMemoryDailySpendCounter fake = new InMemoryDailySpendCounter();
        fake.seed(DAY, CAMPAIGN_ID, 100L, 0L, false);
        PointDeductorImpl real = deductorWith(fake);
        UUID serveId = UUID.randomUUID();

        DeductionStatus first = real.deduct(serveId, CAMPAIGN_ID, DAY);
        DeductionStatus second = real.deduct(serveId, CAMPAIGN_ID, DAY);

        assertThat(second).isEqualTo(first);
        assertThat(fake.spendOf(DAY, CAMPAIGN_ID)).isEqualTo(10L);
    }

    @Test
    void 중단된_캠페인은_Fake에서도_원장을_남기지_않는다() {
        InMemoryDailySpendCounter fake = new InMemoryDailySpendCounter();
        fake.seed(DAY, CAMPAIGN_ID, 100L, 0L, true);

        DeductionStatus status = deductorWith(fake).deduct(UUID.randomUUID(), CAMPAIGN_ID, DAY);

        assertThat(status).isEqualTo(DeductionStatus.NOT_BILLED_PAUSED);
        verify(ledgerRepository, never()).insertDeductIfAbsent(any(), any(), any(UUID.class), anyLong(), any());
    }

    private static final Long OTHER_CAMPAIGN_ID = 8L;
    private static final PlanSnapshot OTHER_OWNER_PLAN = new PlanSnapshot(4L, 10L);

    /*
     * 묶음 차감 시험은 소진 해시를 인메모리 Fake 로, 판정과 원장 저장을 트랜잭션을 따르는 맵으로 둔다.
     * 롤백되면 그 트랜잭션이 넣은 판정과 원장이 사라진다. 점주별로 다시 처리할 때 새 판정으로 보여야 해서다.
     */
    private final InMemoryDailySpendCounter counter = new InMemoryDailySpendCounter();
    private final java.util.Map<UUID, DeductionStatus> storedResults = new java.util.HashMap<>();
    private final List<DeductionBatchRepository.DeductRow> storedDeducts = new ArrayList<>();
    private final java.util.Set<String> missingPlans = new java.util.HashSet<>();
    private final java.util.Map<String, LocalDateTime> releasedPlans = new java.util.HashMap<>();

    private PointDeductorImpl batchDeductor() {
        return new PointDeductorImpl(snapshotService, rebuildService, counter, deductionResultRepository,
                ledgerRepository, campaignQuery, platformSetting, CLOCK, meterRegistry, planRepository, batchRepository,
                transactions);
    }

    // 묶음 차감은 트랜잭션 밖에서 부르므로 setUp 이 켠 동기화를 끄고, 두 점주의 계획과 소진 해시를 둔다
    private void outsideTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
        lenient().when(snapshotService.find(DAY, OTHER_CAMPAIGN_ID)).thenReturn(Optional.of(OTHER_OWNER_PLAN));
        counter.rebuildIfAbsent(DAY, CAMPAIGN_ID, 1000L, 0L, false, null);
        counter.rebuildIfAbsent(DAY, OTHER_CAMPAIGN_ID, 1000L, 0L, false, null);
        lenient().when(planRepository.findAllForShareIn(any(), any())).thenAnswer(invocation -> {
            java.util.Collection<LocalDate> dates = invocation.getArgument(0);
            java.util.Collection<Long> campaignIds = invocation.getArgument(1);
            List<BudgetDailyPlan> plans = new ArrayList<>();
            for (Long campaignId : campaignIds) {
                for (LocalDate date : dates) {
                    if (!missingPlans.contains(campaignId + ":" + date)) {
                        BudgetDailyPlan plan = BudgetDailyPlan.create(campaignId, 3L, date, 1000L, 10L, false, null);
                        ReflectionTestUtils.setField(plan, "releasedAt", releasedPlans.get(campaignId + ":" + date));
                        plans.add(plan);
                    }
                }
            }
            return plans;
        });
        lenient().when(batchRepository.insertResults(any(), any())).thenAnswer(invocation -> {
            List<DeductionBatchRepository.ResultRow> rows = invocation.getArgument(0);
            java.util.Map<UUID, DeductionBatchRepository.StoredResult> stored = new java.util.HashMap<>();
            List<UUID> inserted = new ArrayList<>();
            for (DeductionBatchRepository.ResultRow row : rows) {
                DeductionStatus first = storedResults.putIfAbsent(row.serveId(), row.status());
                if (first == null) {
                    inserted.add(row.serveId());
                }
                stored.put(row.serveId(), new DeductionBatchRepository.StoredResult(
                        first == null ? row.status() : first, first == null));
            }
            onRollback(() -> inserted.forEach(storedResults::remove));
            return stored;
        });
        lenient().doAnswer(invocation -> {
            List<DeductionBatchRepository.DeductRow> rows = invocation.getArgument(0);
            storedDeducts.addAll(rows);
            onRollback(() -> storedDeducts.removeAll(rows));
            return null;
        }).when(batchRepository).insertDeducts(any(), any());
    }

    private static void onRollback(Runnable undo) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    undo.run();
                }
            }
        });
    }

    private long spend(Long campaignId) {
        return counter.readAll(DAY, List.of(campaignId)).get(campaignId).spend();
    }

    @Test
    void 묶음_전체를_한_트랜잭션에서_한_번씩_쓰고_노출마다_판정을_돌려준다() {
        outsideTransaction();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        List<DeductionOutcome> outcomes = batchDeductor().deductAll(List.of(
                new DeductionRequest(first, CAMPAIGN_ID, DAY),
                new DeductionRequest(second, OTHER_CAMPAIGN_ID, DAY),
                new DeductionRequest(third, CAMPAIGN_ID, DAY)));

        assertThat(outcomes).containsExactlyInAnyOrder(
                DeductionOutcome.judged(first, DeductionStatus.BILLED),
                DeductionOutcome.judged(second, DeductionStatus.BILLED),
                DeductionOutcome.judged(third, DeductionStatus.BILLED));
        assertThat(finishedTransactions).containsExactly("commit");
        verify(planRepository).findAllForShareIn(any(), any());
        verify(batchRepository).insertResults(any(), any());
        verify(batchRepository).insertDeducts(any(), any());
        assertThat(storedDeducts).extracting(DeductionBatchRepository.DeductRow::serveId)
                .containsExactlyInAnyOrder(first, second, third);
        assertThat(spend(CAMPAIGN_ID)).isEqualTo(20L);
    }

    @Test
    void 한_트랜잭션이_실패하면_소진을_되돌리고_점주별로_다시_처리해_실패한_점주만_실패로_돌려준다() {
        outsideTransaction();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        doThrow(new IllegalStateException("원장 쓰기 실패")).when(batchRepository).insertDeducts(
                org.mockito.ArgumentMatchers.argThat(rows -> rows.stream().anyMatch(row -> row.ownerId() == 3L)), any());

        List<DeductionOutcome> outcomes = batchDeductor().deductAll(List.of(
                new DeductionRequest(first, CAMPAIGN_ID, DAY),
                new DeductionRequest(second, OTHER_CAMPAIGN_ID, DAY),
                new DeductionRequest(third, CAMPAIGN_ID, DAY)));

        assertThat(outcomes).containsExactlyInAnyOrder(
                DeductionOutcome.failed(first),
                DeductionOutcome.judged(second, DeductionStatus.BILLED),
                DeductionOutcome.failed(third));
        assertThat(finishedTransactions).containsExactly("rollback", "rollback", "commit");
        assertThat(spend(CAMPAIGN_ID)).isZero();
        assertThat(spend(OTHER_CAMPAIGN_ID)).isEqualTo(10L);
        assertThat(storedResults).containsOnlyKeys(second);
        assertThat(meterRegistry.counter(PointDeductorImpl.BATCH_FALLBACK_METRIC).count()).isEqualTo(1);
        assertThat(meterRegistry.counter(PointDeductorImpl.BATCH_FAILED_METRIC, "reason", "owner_group").count())
                .isEqualTo(2);
        // 묶음 트랜잭션에서 셋, 점주 3 의 다시 처리에서 둘을 되돌린다
        assertThat(undoCount("ok")).isEqualTo(5);
    }

    @Test
    void 묶음_안의_영업일이_달라도_노출마다_그_계획의_해제로_마감을_판정한다() {
        outsideTransaction();
        LocalDate yesterday = DAY.minusDays(1);
        when(snapshotService.find(yesterday, CAMPAIGN_ID)).thenReturn(Optional.of(PLAN));
        releasedPlans.put(CAMPAIGN_ID + ":" + yesterday, NOW.minusHours(11));
        UUID today = UUID.randomUUID();
        UUID late = UUID.randomUUID();

        List<DeductionOutcome> outcomes = batchDeductor().deductAll(List.of(
                new DeductionRequest(today, CAMPAIGN_ID, DAY),
                new DeductionRequest(late, CAMPAIGN_ID, yesterday)));

        assertThat(outcomes).containsExactlyInAnyOrder(
                DeductionOutcome.judged(today, DeductionStatus.BILLED),
                DeductionOutcome.judged(late, DeductionStatus.NOT_BILLED_CLOSED));
        assertThat(finishedTransactions).containsExactly("commit");
        assertThat(counter.readAll(yesterday, List.of(CAMPAIGN_ID))).isEmpty();
    }

    // 해제 전이면 자정이 지나도 전날 예산으로 과금하고, 어제보다 앞선 날은 계획을 잠그지 않고 마감으로 본다
    @Test
    void 해제_전의_전날_노출은_과금하고_어제보다_앞선_날은_잠그지_않고_마감으로_본다() {
        outsideTransaction();
        LocalDate yesterday = DAY.minusDays(1);
        LocalDate twoDaysAgo = DAY.minusDays(2);
        when(snapshotService.find(yesterday, CAMPAIGN_ID)).thenReturn(Optional.of(PLAN));
        when(snapshotService.find(twoDaysAgo, CAMPAIGN_ID)).thenReturn(Optional.of(PLAN));
        counter.rebuildIfAbsent(yesterday, CAMPAIGN_ID, 1000L, 0L, false, null);
        UUID late = UUID.randomUUID();
        UUID old = UUID.randomUUID();

        List<DeductionOutcome> outcomes = batchDeductor().deductAll(List.of(
                new DeductionRequest(late, CAMPAIGN_ID, yesterday),
                new DeductionRequest(old, CAMPAIGN_ID, twoDaysAgo)));

        assertThat(outcomes).containsExactlyInAnyOrder(
                DeductionOutcome.judged(late, DeductionStatus.BILLED),
                DeductionOutcome.judged(old, DeductionStatus.NOT_BILLED_CLOSED));
        verify(planRepository).findAllForShareIn(java.util.Set.of(yesterday), java.util.Set.of(CAMPAIGN_ID));
    }

    @Test
    void 이미_판정된_노출을_다시_넘기면_첫_판정을_돌려주고_원장은_쓰지_않는다() {
        outsideTransaction();
        UUID replayed = UUID.randomUUID();
        storedResults.put(replayed, DeductionStatus.NOT_BILLED_BUDGET);

        List<DeductionOutcome> outcomes = batchDeductor().deductAll(List.of(new DeductionRequest(replayed, CAMPAIGN_ID, DAY)));

        assertThat(outcomes).containsExactly(DeductionOutcome.judged(replayed, DeductionStatus.NOT_BILLED_BUDGET));
        verify(batchRepository, never()).insertDeducts(any(), any());
    }

    @Test
    void 같은_노출이_묶음에_둘이면_한_번만_판정하고_같은_결과를_돌려준다() {
        outsideTransaction();
        UUID twice = UUID.randomUUID();

        List<DeductionOutcome> outcomes = batchDeductor().deductAll(List.of(
                new DeductionRequest(twice, CAMPAIGN_ID, DAY),
                new DeductionRequest(twice, CAMPAIGN_ID, DAY)));

        assertThat(outcomes).containsExactly(
                DeductionOutcome.judged(twice, DeductionStatus.BILLED),
                DeductionOutcome.judged(twice, DeductionStatus.BILLED));
        assertThat(spend(CAMPAIGN_ID)).isEqualTo(10L);
        assertThat(storedDeducts).hasSize(1);
    }

    @Test
    void 해시가_없는_캠페인은_다시_만든_뒤_그_노출만_다시_판정한다() {
        outsideTransaction();
        counter.evict(DAY, CAMPAIGN_ID);
        org.mockito.Mockito.doAnswer(invocation -> counter.rebuildIfAbsent(DAY, CAMPAIGN_ID, 1000L, 50L, false, null))
                .when(rebuildService).rebuildIfAbsent(DAY, CAMPAIGN_ID);
        UUID missing = UUID.randomUUID();
        UUID present = UUID.randomUUID();

        List<DeductionOutcome> outcomes = batchDeductor().deductAll(List.of(
                new DeductionRequest(missing, CAMPAIGN_ID, DAY),
                new DeductionRequest(present, OTHER_CAMPAIGN_ID, DAY)));

        assertThat(outcomes).containsExactlyInAnyOrder(
                DeductionOutcome.judged(missing, DeductionStatus.BILLED),
                DeductionOutcome.judged(present, DeductionStatus.BILLED));
        assertThat(spend(CAMPAIGN_ID)).isEqualTo(60L);
        assertThat(spend(OTHER_CAMPAIGN_ID)).isEqualTo(10L);
    }

    @Test
    void 다시_만들어도_해시가_없으면_그_점주_묶음만_실패한다() {
        outsideTransaction();
        counter.evict(DAY, CAMPAIGN_ID);
        UUID missing = UUID.randomUUID();
        UUID present = UUID.randomUUID();

        List<DeductionOutcome> outcomes = batchDeductor().deductAll(List.of(
                new DeductionRequest(missing, CAMPAIGN_ID, DAY),
                new DeductionRequest(present, OTHER_CAMPAIGN_ID, DAY)));

        assertThat(outcomes).containsExactlyInAnyOrder(
                DeductionOutcome.failed(missing),
                DeductionOutcome.judged(present, DeductionStatus.BILLED));
        assertThat(spend(OTHER_CAMPAIGN_ID)).isEqualTo(10L);
    }

    @Test
    void 계획_스냅샷은_있는데_계획_행이_없으면_그_점주_묶음만_실패한다() {
        outsideTransaction();
        missingPlans.add(CAMPAIGN_ID + ":" + DAY);
        UUID broken = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        List<DeductionOutcome> outcomes = batchDeductor().deductAll(List.of(
                new DeductionRequest(broken, CAMPAIGN_ID, DAY),
                new DeductionRequest(other, OTHER_CAMPAIGN_ID, DAY)));

        assertThat(outcomes).containsExactlyInAnyOrder(
                DeductionOutcome.failed(broken),
                DeductionOutcome.judged(other, DeductionStatus.BILLED));
    }

    // 계획 잠금 대기 초과는 그 계획을 다투는 해제 때문이라 그 점주 묶음만 실패한다
    @Test
    void 한_점주의_계획_잠금_대기가_넘치면_그_점주_묶음만_실패한다() {
        outsideTransaction();
        doThrow(new CannotAcquireLockException("잠금 대기 초과")).when(planRepository)
                .findAllForShareIn(any(), org.mockito.ArgumentMatchers.argThat(ids -> ids.contains(CAMPAIGN_ID)));
        UUID blocked = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        List<DeductionOutcome> outcomes = batchDeductor().deductAll(List.of(
                new DeductionRequest(blocked, CAMPAIGN_ID, DAY),
                new DeductionRequest(other, OTHER_CAMPAIGN_ID, DAY)));

        assertThat(outcomes).containsExactlyInAnyOrder(
                DeductionOutcome.failed(blocked),
                DeductionOutcome.judged(other, DeductionStatus.BILLED));
    }

    @Test
    void 계획도_캠페인도_없는_노출은_그_노출만_실패로_돌려주고_나머지는_처리한다() {
        outsideTransaction();
        Long missingCampaign = 99L;
        when(snapshotService.find(DAY, missingCampaign)).thenReturn(Optional.empty());
        when(campaignQuery.findAllById(List.of(missingCampaign))).thenReturn(List.of());
        UUID unknown = UUID.randomUUID();
        UUID known = UUID.randomUUID();

        List<DeductionOutcome> outcomes = batchDeductor().deductAll(List.of(
                new DeductionRequest(unknown, missingCampaign, DAY),
                new DeductionRequest(known, CAMPAIGN_ID, DAY)));

        assertThat(outcomes).containsExactlyInAnyOrder(
                DeductionOutcome.failed(unknown),
                DeductionOutcome.judged(known, DeductionStatus.BILLED));
        assertThat(meterRegistry.counter(PointDeductorImpl.BATCH_FAILED_METRIC, "reason", "unknown_campaign").count())
                .isEqualTo(1);
    }

    @Test
    void 계획이_없는_캠페인의_노출은_캠페인의_점주로_계획_없음을_기록하고_지난_날은_마감으로_기록한다() {
        outsideTransaction();
        Long noPlanCampaign = 50L;
        LocalDate yesterday = DAY.minusDays(1);
        when(snapshotService.find(DAY, noPlanCampaign)).thenReturn(Optional.empty());
        when(snapshotService.find(yesterday, noPlanCampaign)).thenReturn(Optional.empty());
        when(campaignQuery.findAllById(List.of(noPlanCampaign))).thenReturn(List.of(campaign(noPlanCampaign, 5L)));
        when(platformSetting.getImpressionUnitPrice()).thenReturn(12);
        UUID today = UUID.randomUUID();
        UUID late = UUID.randomUUID();

        List<DeductionOutcome> outcomes = batchDeductor().deductAll(List.of(
                new DeductionRequest(today, noPlanCampaign, DAY),
                new DeductionRequest(late, noPlanCampaign, yesterday)));

        assertThat(outcomes).containsExactlyInAnyOrder(
                DeductionOutcome.judged(today, DeductionStatus.NOT_BILLED_NO_PLAN),
                DeductionOutcome.judged(late, DeductionStatus.NOT_BILLED_CLOSED));
        verify(batchRepository).insertResults(List.of(
                new DeductionBatchRepository.ResultRow(today, noPlanCampaign, 5L, DAY, DeductionStatus.NOT_BILLED_NO_PLAN, 12L),
                new DeductionBatchRepository.ResultRow(late, noPlanCampaign, 5L, yesterday, DeductionStatus.NOT_BILLED_CLOSED, 12L)),
                NOW);
        verify(planRepository, never()).findAllForShareIn(any(), any());
        assertThat(meterRegistry.counter(PointDeductorImpl.NO_PLAN_METRIC).count()).isEqualTo(1);
    }

    /*
     * 정산의 Valkey 나 DB 에 닿지 못하면 모든 점주 묶음이 같은 이유로 실패한다. 점주별로 다시 처리하면 릴레이의 재시도 표만 커지므로
     * 예외로 올려 릴레이가 같은 묶음을 다음 주기에 다시 넘기게 한다.
     */
    @Test
    void 정산이_Valkey에_닿지_못하면_점주별로_다시_처리하지_않고_예외를_올린다() {
        outsideTransaction();
        when(spendCounter.deductAll(any())).thenThrow(new RedisConnectionFailureException("Valkey 연결 실패"));

        assertThatThrownBy(() -> deductor.deductAll(List.of(
                new DeductionRequest(UUID.randomUUID(), CAMPAIGN_ID, DAY),
                new DeductionRequest(UUID.randomUUID(), OTHER_CAMPAIGN_ID, DAY))))
                .isInstanceOf(RedisConnectionFailureException.class);
        assertThat(finishedTransactions).containsExactly("rollback");
    }

    @Test
    void 정산의_명령이_시간을_넘기면_예외를_올린다() {
        outsideTransaction();
        when(spendCounter.deductAll(any())).thenThrow(new QueryTimeoutException("Valkey 명령 시간 초과"));

        assertThatThrownBy(() -> deductor.deductAll(List.of(new DeductionRequest(UUID.randomUUID(), CAMPAIGN_ID, DAY))))
                .isInstanceOf(QueryTimeoutException.class);
    }

    @Test
    void 점주별로_다시_처리하다_DB에_닿지_못하면_예외를_올린다() {
        outsideTransaction();
        doThrow(new CannotAcquireLockException("잠금 대기 초과"), new QueryTimeoutException("DB 응답 없음"))
                .when(planRepository).findAllForShareIn(any(), any());

        assertThatThrownBy(() -> batchDeductor().deductAll(List.of(
                new DeductionRequest(UUID.randomUUID(), CAMPAIGN_ID, DAY),
                new DeductionRequest(UUID.randomUUID(), OTHER_CAMPAIGN_ID, DAY))))
                .isInstanceOf(QueryTimeoutException.class);
        assertThat(finishedTransactions).containsExactly("rollback", "rollback");
    }

    @Test
    void 롤백된_판정을_되돌리지_못해도_점주별로_다시_처리하고_실패를_센다() {
        outsideTransaction();
        PointDeductorImpl withBrokenUndo = new PointDeductorImpl(snapshotService, rebuildService, spendCounter,
                deductionResultRepository, ledgerRepository, campaignQuery, platformSetting, CLOCK, meterRegistry,
                planRepository, batchRepository, transactions);
        when(spendCounter.deductAll(any())).thenAnswer(invocation -> counter.deductAll(invocation.getArgument(0)));
        doThrow(new IllegalStateException("되돌리기 실패")).when(spendCounter).undoAll(any());
        doThrow(new IllegalStateException("원장 쓰기 실패")).when(batchRepository).insertDeducts(any(), any());
        UUID serveId = UUID.randomUUID();

        List<DeductionOutcome> outcomes = withBrokenUndo.deductAll(List.of(new DeductionRequest(serveId, CAMPAIGN_ID, DAY)));

        assertThat(outcomes).containsExactly(DeductionOutcome.failed(serveId));
        assertThat(undoCount("failed")).isEqualTo(1);
    }

    /*
     * DB 가 멈췄거나 커넥션 풀이 마르면 트랜잭션을 여는 순간 실패한다.
     * 점주별로 다시 처리하면 점주마다 커넥션 대기(3초)를 되풀이하므로 바로 예외를 올린다.
     */
    @Test
    void 트랜잭션을_열지_못하면_점주별로_다시_처리하지_않고_예외를_올린다() {
        outsideTransaction();
        List<String> begins = new ArrayList<>();
        TransactionTemplate noConnection = new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                begins.add("begin");
                throw new CannotCreateTransactionException("커넥션을 얻지 못했습니다");
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        });
        PointDeductorImpl withoutDb = new PointDeductorImpl(snapshotService, rebuildService, counter,
                deductionResultRepository, ledgerRepository, campaignQuery, platformSetting, CLOCK, meterRegistry,
                planRepository, batchRepository, noConnection);

        assertThatThrownBy(() -> withoutDb.deductAll(List.of(
                new DeductionRequest(UUID.randomUUID(), CAMPAIGN_ID, DAY),
                new DeductionRequest(UUID.randomUUID(), OTHER_CAMPAIGN_ID, DAY))))
                .isInstanceOf(CannotCreateTransactionException.class);
        assertThat(begins).hasSize(1);
        assertThat(spend(CAMPAIGN_ID)).isZero();
    }

    @Test
    void 묶음_차감은_상한까지_받고_넘으면_아무것도_하지_않고_거절한다() {
        outsideTransaction();
        List<DeductionRequest> atLimit = new ArrayList<>();
        for (int i = 0; i < PointDeductor.MAX_BATCH_SIZE; i++) {
            atLimit.add(new DeductionRequest(UUID.randomUUID(), CAMPAIGN_ID, DAY));
        }

        assertThat(batchDeductor().deductAll(atLimit)).hasSize(PointDeductor.MAX_BATCH_SIZE);

        List<DeductionRequest> overLimit = new ArrayList<>(atLimit);
        overLimit.add(new DeductionRequest(UUID.randomUUID(), CAMPAIGN_ID, DAY));
        assertThatThrownBy(() -> batchDeductor().deductAll(overLimit)).isInstanceOf(IllegalArgumentException.class);
        assertThat(finishedTransactions).containsExactly("commit");
    }

    @Test
    void 빈_목록이면_트랜잭션을_열지_않고_빈_결과를_돌려준다() {
        outsideTransaction();

        assertThat(deductor.deductAll(List.of())).isEmpty();
        assertThat(finishedTransactions).isEmpty();
    }

    @Test
    void 묶음_차감을_트랜잭션_안에서_부르면_거절한다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> deductor.deductAll(List.of(new DeductionRequest(SERVE_ID, CAMPAIGN_ID, DAY))))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(spendCounter, deductionResultRepository);
    }
}
