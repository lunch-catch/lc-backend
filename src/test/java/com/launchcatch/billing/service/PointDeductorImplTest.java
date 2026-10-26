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
import com.launchcatch.billing.repository.PointAccountRepository;
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
    private PointAccountRepository accountRepository;

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
                accountRepository, transactions);
        lenient().when(snapshotService.find(DAY, CAMPAIGN_ID)).thenReturn(Optional.of(PLAN));
        lenient().when(accountRepository.lockForShareByOwnerId(3L)).thenReturn(Optional.of(1L));
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

    @Test
    void 자정이_지난_전날_노출은_미과금으로_남기고_카운터와_원장은_건드리지_않는다() {
        LocalDate yesterday = DAY.minusDays(1);
        when(snapshotService.find(yesterday, CAMPAIGN_ID)).thenReturn(Optional.of(PLAN));
        lenient().when(spendCounter.deduct(yesterday, CAMPAIGN_ID, SERVE_ID, 10L))
                .thenReturn(DeductOutcome.of(DeductionStatus.BILLED));

        DeductionStatus status = deductor.deduct(SERVE_ID, CAMPAIGN_ID, yesterday);

        assertThat(status.name()).isEqualTo("NOT_BILLED_CLOSED");
        verifyNoInteractions(spendCounter, rebuildService, ledgerRepository);
    }

    @Test
    void KST_자정_정각부터_전날_노출을_과금하지_않는다() {
        Clock midnight = Clock.fixed(Instant.parse("2026-10-14T15:00:00Z"), ClockConfig.ZONE);

        DeductionStatus status = deductorAt(midnight).deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        assertThat(status).isEqualTo(DeductionStatus.NOT_BILLED_CLOSED);
        verify(deductionResultRepository).insertIfAbsent(SERVE_ID, CAMPAIGN_ID, 3L, DAY,
                DeductionStatus.NOT_BILLED_CLOSED, 10L, DAY.plusDays(1).atStartOfDay());
        verifyNoInteractions(spendCounter, rebuildService, ledgerRepository);
    }

    @Test
    void KST_자정_직전에는_그날_예산으로_과금한다() {
        Clock beforeMidnight = Clock.fixed(Instant.parse("2026-10-14T14:59:59.999999Z"), ClockConfig.ZONE);
        when(spendCounter.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L))
                .thenReturn(DeductOutcome.of(DeductionStatus.BILLED));

        assertThat(deductorAt(beforeMidnight).deduct(SERVE_ID, CAMPAIGN_ID, DAY))
                .isEqualTo(DeductionStatus.BILLED);
    }

    @Test
    void 계정_잠금을_기다리는_동안_자정이_지나면_전날_노출을_과금하지_않는다() {
        AtomicReference<Instant> instant = new AtomicReference<>(Instant.parse("2026-10-14T14:59:59Z"));
        Clock changingClock = mock(Clock.class);
        when(changingClock.getZone()).thenReturn(ClockConfig.ZONE);
        when(changingClock.instant()).thenAnswer(invocation -> instant.get());
        when(accountRepository.lockForShareByOwnerId(3L)).thenAnswer(invocation -> {
            instant.set(Instant.parse("2026-10-14T15:00:00Z"));
            return Optional.of(1L);
        });

        DeductionStatus status = deductorAt(changingClock).deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        assertThat(status).isEqualTo(DeductionStatus.NOT_BILLED_CLOSED);
        verifyNoInteractions(spendCounter, rebuildService, ledgerRepository);
    }

    @Test
    void 마감_뒤_재호출은_이미_커밋된_과금_판정을_그대로_돌려준다() {
        LocalDate yesterday = DAY.minusDays(1);
        when(snapshotService.find(yesterday, CAMPAIGN_ID)).thenReturn(Optional.of(PLAN));
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
    void 계획의_점주_계정이_없으면_과금하지_않고_실패한다() {
        when(accountRepository.lockForShareByOwnerId(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ownerId=3");
        verifyNoInteractions(spendCounter, rebuildService, ledgerRepository, deductionResultRepository);
    }

    private PointDeductorImpl deductorAt(Clock time) {
        return new PointDeductorImpl(snapshotService, rebuildService, spendCounter, deductionResultRepository,
                ledgerRepository, campaignQuery, platformSetting, time, meterRegistry, accountRepository, transactions);
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
                meterRegistry, accountRepository, transactions);
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

    // 묶음 차감은 트랜잭션 밖에서 부르므로 setUp 이 켠 동기화를 끄고 시작한다
    private void outsideTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private void otherOwnerCampaignBills() {
        lenient().when(snapshotService.find(DAY, OTHER_CAMPAIGN_ID)).thenReturn(Optional.of(OTHER_OWNER_PLAN));
        lenient().when(accountRepository.lockForShareByOwnerId(4L)).thenReturn(Optional.of(2L));
        lenient().when(spendCounter.deduct(any(), any(), any(UUID.class), anyLong()))
                .thenReturn(DeductOutcome.of(DeductionStatus.BILLED));
    }

    @Test
    void 묶음_차감은_점주별로_묶어_점주마다_한_트랜잭션으로_처리하고_노출마다_판정을_돌려준다() {
        outsideTransaction();
        otherOwnerCampaignBills();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        List<DeductionOutcome> outcomes = deductor.deductAll(List.of(
                new DeductionRequest(first, CAMPAIGN_ID, DAY),
                new DeductionRequest(second, OTHER_CAMPAIGN_ID, DAY),
                new DeductionRequest(third, CAMPAIGN_ID, DAY)));

        assertThat(outcomes).containsExactlyInAnyOrder(
                DeductionOutcome.judged(first, DeductionStatus.BILLED),
                DeductionOutcome.judged(second, DeductionStatus.BILLED),
                DeductionOutcome.judged(third, DeductionStatus.BILLED));
        assertThat(finishedTransactions).containsExactly("commit", "commit");
        verify(accountRepository, times(2)).lockForShareByOwnerId(3L);
        verify(accountRepository).lockForShareByOwnerId(4L);
    }

    @Test
    void 한_점주_묶음이_실패하면_그_묶음만_실패로_돌려주고_소진을_되돌리며_다른_점주_묶음은_커밋된다() {
        outsideTransaction();
        otherOwnerCampaignBills();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        lenient().doThrow(new IllegalStateException("원장 쓰기 실패"))
                .when(ledgerRepository).insertDeductIfAbsent(3L, CAMPAIGN_ID, third, -10L, NOW);

        List<DeductionOutcome> outcomes = deductor.deductAll(List.of(
                new DeductionRequest(first, CAMPAIGN_ID, DAY),
                new DeductionRequest(second, OTHER_CAMPAIGN_ID, DAY),
                new DeductionRequest(third, CAMPAIGN_ID, DAY)));

        assertThat(outcomes).containsExactlyInAnyOrder(
                DeductionOutcome.failed(first),
                DeductionOutcome.judged(second, DeductionStatus.BILLED),
                DeductionOutcome.failed(third));
        assertThat(finishedTransactions).containsExactlyInAnyOrder("rollback", "commit");
        verify(spendCounter).undo(DAY, CAMPAIGN_ID, first, 10L);
        verify(spendCounter).undo(DAY, CAMPAIGN_ID, third, 10L);
        verify(spendCounter, never()).undo(DAY, OTHER_CAMPAIGN_ID, second, 10L);
        assertThat(meterRegistry.counter(PointDeductorImpl.BATCH_FAILED_METRIC, "reason", "owner_group").count())
                .isEqualTo(2);
    }

    @Test
    void 묶음_안의_영업일이_달라도_노출마다_받은_날로_마감을_판정한다() {
        outsideTransaction();
        otherOwnerCampaignBills();
        LocalDate yesterday = DAY.minusDays(1);
        when(snapshotService.find(yesterday, CAMPAIGN_ID)).thenReturn(Optional.of(PLAN));
        UUID today = UUID.randomUUID();
        UUID late = UUID.randomUUID();

        List<DeductionOutcome> outcomes = deductor.deductAll(List.of(
                new DeductionRequest(today, CAMPAIGN_ID, DAY),
                new DeductionRequest(late, CAMPAIGN_ID, yesterday)));

        assertThat(outcomes).containsExactlyInAnyOrder(
                DeductionOutcome.judged(today, DeductionStatus.BILLED),
                DeductionOutcome.judged(late, DeductionStatus.NOT_BILLED_CLOSED));
        assertThat(finishedTransactions).containsExactly("commit");
        verify(spendCounter, never()).deduct(yesterday, CAMPAIGN_ID, late, 10L);
    }

    @Test
    void 이미_판정된_노출을_다시_넘기면_첫_판정을_돌려준다() {
        outsideTransaction();
        otherOwnerCampaignBills();
        UUID replayed = UUID.randomUUID();
        when(deductionResultRepository.insertIfAbsent(any(UUID.class), any(), any(), any(),
                any(DeductionStatus.class), anyLong(), any())).thenReturn(0);
        when(deductionResultRepository.findFirstStatus(replayed)).thenReturn(Optional.of(DeductionStatus.NOT_BILLED_BUDGET));

        List<DeductionOutcome> outcomes = deductor.deductAll(List.of(new DeductionRequest(replayed, CAMPAIGN_ID, DAY)));

        assertThat(outcomes).containsExactly(DeductionOutcome.judged(replayed, DeductionStatus.NOT_BILLED_BUDGET));
        verify(ledgerRepository, never()).insertDeductIfAbsent(any(), any(), any(UUID.class), anyLong(), any());
    }

    @Test
    void 계획도_캠페인도_없는_노출은_그_노출만_실패로_돌려주고_나머지는_처리한다() {
        outsideTransaction();
        otherOwnerCampaignBills();
        Long missingCampaign = 99L;
        when(snapshotService.find(DAY, missingCampaign)).thenReturn(Optional.empty());
        when(campaignQuery.findAllById(List.of(missingCampaign))).thenReturn(List.of());
        UUID unknown = UUID.randomUUID();
        UUID known = UUID.randomUUID();

        List<DeductionOutcome> outcomes = deductor.deductAll(List.of(
                new DeductionRequest(unknown, missingCampaign, DAY),
                new DeductionRequest(known, CAMPAIGN_ID, DAY)));

        assertThat(outcomes).containsExactlyInAnyOrder(
                DeductionOutcome.failed(unknown),
                DeductionOutcome.judged(known, DeductionStatus.BILLED));
        assertThat(meterRegistry.counter(PointDeductorImpl.BATCH_FAILED_METRIC, "reason", "unknown_campaign").count())
                .isEqualTo(1);
    }

    /*
     * 정산의 Valkey 나 DB 에 닿지 못하면 모든 점주 묶음이 같은 이유로 실패한다. 점주별 실패로 돌려주면 릴레이의 재시도 표만 커지므로
     * 예외로 올려 릴레이가 같은 묶음을 다음 주기에 다시 넘기게 한다.
     */
    @Test
    void 정산이_Valkey에_닿지_못하면_묶음별_실패가_아니라_예외를_올린다() {
        outsideTransaction();
        otherOwnerCampaignBills();
        when(spendCounter.deduct(any(), any(), any(UUID.class), anyLong()))
                .thenThrow(new RedisConnectionFailureException("Valkey 연결 실패"));

        assertThatThrownBy(() -> deductor.deductAll(List.of(
                new DeductionRequest(UUID.randomUUID(), CAMPAIGN_ID, DAY),
                new DeductionRequest(UUID.randomUUID(), OTHER_CAMPAIGN_ID, DAY))))
                .isInstanceOf(RedisConnectionFailureException.class);
        assertThat(finishedTransactions).containsExactly("rollback");
    }

    @Test
    void 정산의_명령이_시간을_넘기면_예외를_올린다() {
        outsideTransaction();
        otherOwnerCampaignBills();
        when(spendCounter.deduct(any(), any(), any(UUID.class), anyLong()))
                .thenThrow(new QueryTimeoutException("Valkey 명령 시간 초과"));

        assertThatThrownBy(() -> deductor.deductAll(List.of(new DeductionRequest(UUID.randomUUID(), CAMPAIGN_ID, DAY))))
                .isInstanceOf(QueryTimeoutException.class);
    }

    // 계정 잠금 대기 초과는 그 점주의 잠금을 다투는 해제나 환불 때문이라 그 점주 묶음만 실패한다
    @Test
    void 한_점주의_잠금_대기가_넘치면_그_점주_묶음만_실패한다() {
        outsideTransaction();
        otherOwnerCampaignBills();
        when(accountRepository.lockForShareByOwnerId(3L)).thenThrow(new CannotAcquireLockException("잠금 대기 초과"));
        UUID blocked = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        List<DeductionOutcome> outcomes = deductor.deductAll(List.of(
                new DeductionRequest(blocked, CAMPAIGN_ID, DAY),
                new DeductionRequest(other, OTHER_CAMPAIGN_ID, DAY)));

        assertThat(outcomes).containsExactlyInAnyOrder(
                DeductionOutcome.failed(blocked),
                DeductionOutcome.judged(other, DeductionStatus.BILLED));
    }

    @Test
    void 묶음_차감은_상한까지_받고_넘으면_아무것도_하지_않고_거절한다() {
        outsideTransaction();
        otherOwnerCampaignBills();
        List<DeductionRequest> atLimit = new ArrayList<>();
        for (int i = 0; i < PointDeductor.MAX_BATCH_SIZE; i++) {
            atLimit.add(new DeductionRequest(UUID.randomUUID(), CAMPAIGN_ID, DAY));
        }

        assertThat(deductor.deductAll(atLimit)).hasSize(PointDeductor.MAX_BATCH_SIZE);

        List<DeductionRequest> overLimit = new ArrayList<>(atLimit);
        overLimit.add(new DeductionRequest(UUID.randomUUID(), CAMPAIGN_ID, DAY));
        assertThatThrownBy(() -> deductor.deductAll(overLimit)).isInstanceOf(IllegalArgumentException.class);
        assertThat(finishedTransactions).containsExactly("commit");
    }

    @Test
    void 계획이_없는_캠페인의_노출은_캠페인의_점주로_묶어_계획_없음으로_기록한다() {
        outsideTransaction();
        Long noPlanCampaign = 50L;
        when(snapshotService.find(DAY, noPlanCampaign)).thenReturn(Optional.empty());
        when(campaignQuery.findAllById(List.of(noPlanCampaign))).thenReturn(List.of(campaign(noPlanCampaign, 5L)));
        when(platformSetting.getImpressionUnitPrice()).thenReturn(10);
        UUID serveId = UUID.randomUUID();

        List<DeductionOutcome> outcomes = deductor.deductAll(List.of(new DeductionRequest(serveId, noPlanCampaign, DAY)));

        assertThat(outcomes).containsExactly(DeductionOutcome.judged(serveId, DeductionStatus.NOT_BILLED_NO_PLAN));
        verify(deductionResultRepository).insertIfAbsent(serveId, noPlanCampaign, 5L, DAY,
                DeductionStatus.NOT_BILLED_NO_PLAN, 10L, NOW);
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
