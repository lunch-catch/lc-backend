package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.contract.DeductionStatus;
import com.launchcatch.billing.dto.PlanSnapshot;
import com.launchcatch.billing.cache.DailySpendCounter;
import com.launchcatch.billing.cache.InMemoryDailySpendCounter;
import com.launchcatch.billing.cache.DeductOutcome;
import com.launchcatch.billing.repository.DeductionResultRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private PointDeductorImpl deductor;

    @BeforeEach
    void setUp() {
        // 운영에서는 MANDATORY 트랜잭션이 동기화를 켜 둔다. 시험도 같은 상태로 만든다
        TransactionSynchronizationManager.initSynchronization();
        deductor = new PointDeductorImpl(snapshotService, rebuildService, spendCounter,
                deductionResultRepository, ledgerRepository, campaignQuery, platformSetting, CLOCK, meterRegistry);
        lenient().when(snapshotService.find(DAY, CAMPAIGN_ID)).thenReturn(Optional.of(PLAN));
        // 기본은 새로 기록된 경우다. 중복은 해당 시험에서 0 을 돌려주게 한다
        lenient().when(deductionResultRepository.insertIfAbsent(any(UUID.class), any(), any(), any(),
                any(DeductionStatus.class), anyLong(), any())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
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
    void 트랜잭션_안에서만_부를_수_있다() throws NoSuchMethodException {
        Transactional transactional = PointDeductorImpl.class
                .getMethod("deduct", UUID.class, Long.class, LocalDate.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
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
                meterRegistry);
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
}
