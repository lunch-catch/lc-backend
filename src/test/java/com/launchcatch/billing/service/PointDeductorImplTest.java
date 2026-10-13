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
import com.launchcatch.billing.repository.DailySpendHashRepository;
import com.launchcatch.billing.repository.DeductOutcome;
import com.launchcatch.billing.repository.DeductionResultRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private DailySpendHashRepository hashRepository;
    @Mock
    private DeductionResultRepository deductionResultRepository;
    @Mock
    private PointLedgerRepository ledgerRepository;

    private PointDeductorImpl deductor;

    @BeforeEach
    void setUp() {
        deductor = new PointDeductorImpl(snapshotService, rebuildService, hashRepository,
                deductionResultRepository, ledgerRepository, CLOCK);
        lenient().when(snapshotService.get(DAY, CAMPAIGN_ID)).thenReturn(PLAN);
    }

    @Test
    void 과금하면_판정을_기록하고_단가의_음수로_원장에_남긴다() {
        when(hashRepository.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L)).thenReturn(DeductOutcome.of(DeductionStatus.BILLED));

        DeductionStatus status = deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        assertThat(status).isEqualTo(DeductionStatus.BILLED);
        verify(deductionResultRepository).insertIfAbsent(SERVE_ID, CAMPAIGN_ID, 3L, DAY, DeductionStatus.BILLED, 10L, NOW);
        verify(ledgerRepository).insertDeductIfAbsent(3L, CAMPAIGN_ID, SERVE_ID, -10L, NOW);
        verify(rebuildService, never()).rebuildIfAbsent(any(), any());
    }

    @Test
    void 예약액에_닿으면_판정만_기록하고_원장은_쓰지_않는다() {
        when(hashRepository.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L)).thenReturn(DeductOutcome.of(DeductionStatus.NOT_BILLED_BUDGET));

        DeductionStatus status = deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        assertThat(status).isEqualTo(DeductionStatus.NOT_BILLED_BUDGET);
        verify(deductionResultRepository).insertIfAbsent(SERVE_ID, CAMPAIGN_ID, 3L, DAY, DeductionStatus.NOT_BILLED_BUDGET, 10L, NOW);
        verify(ledgerRepository, never()).insertDeductIfAbsent(any(), any(), any(UUID.class), anyLong(), any());
    }

    @Test
    void 중단된_캠페인은_판정만_기록하고_원장은_쓰지_않는다() {
        when(hashRepository.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L)).thenReturn(DeductOutcome.of(DeductionStatus.NOT_BILLED_PAUSED));

        DeductionStatus status = deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        assertThat(status).isEqualTo(DeductionStatus.NOT_BILLED_PAUSED);
        verify(deductionResultRepository).insertIfAbsent(SERVE_ID, CAMPAIGN_ID, 3L, DAY, DeductionStatus.NOT_BILLED_PAUSED, 10L, NOW);
        verify(ledgerRepository, never()).insertDeductIfAbsent(any(), any(), any(UUID.class), anyLong(), any());
    }

    @Test
    void 같은_serveId를_다시_부르면_첫_판정을_돌려주고_INSERT는_무변경이다() {
        when(hashRepository.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L)).thenReturn(DeductOutcome.of(DeductionStatus.BILLED));
        when(deductionResultRepository.insertIfAbsent(SERVE_ID, CAMPAIGN_ID, 3L, DAY, DeductionStatus.BILLED, 10L, NOW))
                .thenReturn(1, 0);
        when(ledgerRepository.insertDeductIfAbsent(3L, CAMPAIGN_ID, SERVE_ID, -10L, NOW)).thenReturn(1, 0);

        DeductionStatus first = deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);
        DeductionStatus second = deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        assertThat(first).isEqualTo(DeductionStatus.BILLED);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void 해시가_없으면_재구성_뒤_한_번만_다시_판정한다() {
        when(hashRepository.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L))
                .thenReturn(DeductOutcome.hashMissing(), DeductOutcome.of(DeductionStatus.BILLED));

        DeductionStatus status = deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY);

        assertThat(status).isEqualTo(DeductionStatus.BILLED);
        verify(rebuildService).rebuildIfAbsent(DAY, CAMPAIGN_ID);
        verify(hashRepository, times(2)).deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L);
    }

    @Test
    void 재구성한_뒤에도_해시가_없으면_예외이고_아무것도_기록하지_않는다() {
        when(hashRepository.deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L)).thenReturn(DeductOutcome.hashMissing());

        assertThatThrownBy(() -> deductor.deduct(SERVE_ID, CAMPAIGN_ID, DAY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("campaignId=7");
        verify(hashRepository, times(2)).deduct(DAY, CAMPAIGN_ID, SERVE_ID, 10L);
        verify(deductionResultRepository, never()).insertIfAbsent(any(UUID.class), any(), any(), any(), any(DeductionStatus.class), anyLong(), any());
    }

    @Test
    void 트랜잭션_안에서만_부를_수_있다() throws NoSuchMethodException {
        Transactional transactional = PointDeductorImpl.class
                .getMethod("deduct", UUID.class, Long.class, LocalDate.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
