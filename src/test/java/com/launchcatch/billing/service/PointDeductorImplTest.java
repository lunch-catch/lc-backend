package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.contract.DeductionStatus;
import com.launchcatch.billing.dto.PlanSnapshot;
import com.launchcatch.billing.cache.DailySpendCounter;
import com.launchcatch.billing.cache.InMemoryDailySpendCounter;
import com.launchcatch.billing.cache.DeductOutcome;
import com.launchcatch.billing.repository.DeductionResultRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class PointDeductorImplTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final UUID SERVE_ID = UUID.fromString("0192f3a4-7b5c-7d1e-9a2b-3c4d5e6f7a8b");
    private static final Long CAMPAIGN_ID = 7L;
    private static final PlanSnapshot PLAN = new PlanSnapshot(1L, 3L, 10L);
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

    private PointDeductorImpl deductor;

    @BeforeEach
    void setUp() {
        deductor = new PointDeductorImpl(snapshotService, rebuildService, spendCounter,
                deductionResultRepository, ledgerRepository, CLOCK);
        lenient().when(snapshotService.get(DAY, CAMPAIGN_ID)).thenReturn(PLAN);
        // 기본은 새로 기록된 경우다. 중복은 해당 시험에서 0 을 돌려주게 한다
        lenient().when(deductionResultRepository.insertIfAbsent(any(UUID.class), any(), any(), any(),
                any(DeductionStatus.class), anyLong(), any())).thenReturn(1);
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
                .thenReturn(1, 0);
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
                .thenReturn(0);
        when(deductionResultRepository.findFirstStatus(SERVE_ID))
                .thenReturn(Optional.of(DeductionStatus.NOT_BILLED_BUDGET));

        DeductionStatus status = deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        assertThat(status).isEqualTo(DeductionStatus.NOT_BILLED_BUDGET);
        verify(ledgerRepository, never()).insertDeductIfAbsent(any(), any(), any(UUID.class), anyLong(), any());
    }

    @Test
    void 중복인데_저장된_판정을_읽을_수_없으면_예외다() {
        when(spendCounter.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L)).thenReturn(DeductOutcome.of(DeductionStatus.BILLED));
        when(deductionResultRepository.insertIfAbsent(SERVE_ID, CAMPAIGN_ID, 3L, DAY, DeductionStatus.BILLED, 10L, NOW))
                .thenReturn(0);
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

    // 아래 시험은 Mockito 대신 Lua 와 같은 규칙의 Fake 를 연결해 판정이 쌓이는 모습을 그대로 본다
    private PointDeductorImpl deductorWith(InMemoryDailySpendCounter fake) {
        return new PointDeductorImpl(snapshotService, rebuildService, fake, deductionResultRepository, ledgerRepository, CLOCK);
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
