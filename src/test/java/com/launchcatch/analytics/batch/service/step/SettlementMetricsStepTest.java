package com.launchcatch.analytics.batch.service.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.analytics.batch.PendingReason;
import com.launchcatch.analytics.batch.SettlementMetrics;
import com.launchcatch.analytics.batch.repository.PendingDateRepository;
import com.launchcatch.analytics.batch.repository.SettlementAnalyticsRepository;
import com.launchcatch.analytics.batch.repository.SettlementAnalyticsRow;
import com.launchcatch.billing.contract.BillingStatsQueryService;
import com.launchcatch.billing.contract.DailyLedgerTotals;
import com.launchcatch.billing.contract.OwnerCampaignLedgerSum;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementMetricsStepTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T16:00:00Z"),
            ZoneId.of("Asia/Seoul"));

    @Mock
    private BillingStatsQueryService billing;
    @Mock
    private SettlementAnalyticsRepository repository;
    @Mock
    private PendingDateRepository pending;

    private SettlementMetricsStep step() {
        return new SettlementMetricsStep(billing, repository, pending, CLOCK);
    }

    private static OwnerCampaignLedgerSum sum(Long owner, Long campaign, long charge, long reserve, long deduct,
            long release, long refund, long positiveAdjust, long negativeAdjust) {
        return new OwnerCampaignLedgerSum(owner, campaign, charge, reserve, deduct, release, refund,
                positiveAdjust, negativeAdjust);
    }

    @Test
    void 이름은_지표_라벨로_쓰는_단계_이름이다() {
        assertThat(step().name()).isEqualTo("SettlementMetricsStep");
    }

    @Test
    void 점주와_캠페인별_합계를_더해_플랫폼_하루_합을_만든다() {
        when(billing.ledgerSums(DAY)).thenReturn(List.of(
                sum(1L, 10L, 0, -5000, -3000, 2000, 0, 0, 0),
                sum(2L, 20L, 0, -1000, -400, 600, 0, 250, 0),
                sum(1L, null, 9000, 0, 0, 0, -700, 0, -100)));
        when(billing.dailyTotals(DAY)).thenReturn(new DailyLedgerTotals(12_345, 0, 0));
        when(billing.correctionTargetDates(DAY)).thenReturn(List.of());

        SettlementMetrics metrics = step().collect(DAY);

        assertThat(metrics.charge()).isEqualTo(9000);
        assertThat(metrics.reserve()).isEqualTo(-6000);
        assertThat(metrics.deduct()).isEqualTo(-3400);
        assertThat(metrics.release()).isEqualTo(2600);
        assertThat(metrics.refund()).isEqualTo(-700);
        assertThat(metrics.positiveAdjust()).isEqualTo(250);
        assertThat(metrics.negativeAdjust()).isEqualTo(-100);
        assertThat(metrics.unspentBalance()).isEqualTo(12_345);
    }

    @Test
    void 그날_원장이_없으면_모두_0이고_미소진_잔액만_정산_값이다() {
        when(billing.ledgerSums(DAY)).thenReturn(List.of());
        when(billing.dailyTotals(DAY)).thenReturn(new DailyLedgerTotals(777, 0, 0));
        when(billing.correctionTargetDates(DAY)).thenReturn(List.of());

        SettlementMetrics metrics = step().collect(DAY);

        assertThat(metrics).isEqualTo(new SettlementMetrics(0, 0, 0, 0, 0, 0, 0, 777, List.of()));
    }

    @Test
    void 표의_CHECK에_맞게_금액은_절댓값으로_저장하고_조정액만_부호를_둔다() {
        SettlementMetrics metrics = new SettlementMetrics(9000, -6000, -3400, 2600, -700, 250, -100, 12_345,
                List.of());

        int rows = step().publish(DAY, metrics);

        assertThat(rows).isEqualTo(1);
        ArgumentCaptor<SettlementAnalyticsRow> row = ArgumentCaptor.forClass(SettlementAnalyticsRow.class);
        verify(repository).replace(eq(DAY), row.capture(), any(LocalDateTime.class));
        assertThat(row.getValue()).isEqualTo(
                new SettlementAnalyticsRow(9000, 3400, 6000, 2600, 700, 150, 250, 12_345));
    }

    @Test
    void 정정이_차감보다_커도_금액_열은_음수로_내려가지_않는다() {
        SettlementMetrics metrics = new SettlementMetrics(0, 0, -100, 0, 0, 350, 0, 0, List.of());

        step().publish(DAY, metrics);

        ArgumentCaptor<SettlementAnalyticsRow> row = ArgumentCaptor.forClass(SettlementAnalyticsRow.class);
        verify(repository).replace(eq(DAY), row.capture(), any(LocalDateTime.class));
        assertThat(row.getValue().deductAmount()).isEqualTo(100);
        assertThat(row.getValue().positiveAdjustAmount()).isEqualTo(350);
    }

    @Test
    void 정정_대상_영업일은_ADJUSTED로_미처리에_남긴다() {
        LocalDate target = LocalDate.of(2026, 10, 10);
        SettlementMetrics metrics = new SettlementMetrics(0, 0, 0, 0, 0, 0, 0, 0, List.of(target));

        step().publish(DAY, metrics);

        verify(pending).record(eq(target), eq(PendingReason.ADJUSTED), any(LocalDateTime.class));
    }

    @Test
    void 자기_날짜와_오늘_이후와_보존_기간_밖_정정_대상은_남기지_않는다() {
        LocalDate today = LocalDate.of(2026, 10, 17);
        LocalDate tooOld = today.minusMonths(13).minusDays(1);
        SettlementMetrics metrics = new SettlementMetrics(0, 0, 0, 0, 0, 0, 0, 0,
                List.of(DAY, today, today.plusDays(1), tooOld));

        step().publish(DAY, metrics);

        verify(pending, never()).record(any(), any(), any());
    }

    @Test
    void 보존_기간의_첫날은_남긴다() {
        LocalDate oldest = LocalDate.of(2026, 10, 17).minusMonths(13);
        SettlementMetrics metrics = new SettlementMetrics(0, 0, 0, 0, 0, 0, 0, 0, List.of(oldest));

        step().publish(DAY, metrics);

        verify(pending).record(eq(oldest), eq(PendingReason.ADJUSTED), any(LocalDateTime.class));
    }
}
