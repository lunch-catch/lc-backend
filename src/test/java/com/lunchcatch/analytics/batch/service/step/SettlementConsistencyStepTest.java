package com.lunchcatch.analytics.batch.service.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.analytics.batch.service.AggregationSources;
import com.lunchcatch.analytics.batch.SettlementFindings;
import com.lunchcatch.analytics.batch.repository.SettlementAnalyticsRepository;
import com.lunchcatch.analytics.batch.repository.SettlementAnalyticsRow;
import com.lunchcatch.analytics.batch.service.AggregationMetrics;
import com.lunchcatch.billing.contract.BillingStatsQueryService;
import com.lunchcatch.billing.contract.OwnerCampaignLedgerSum;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementConsistencyStepTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final long CAMPAIGN = 10L;

    @Mock
    private BillingStatsQueryService billing;
    @Mock
    private SettlementAnalyticsRepository repository;

    private SimpleMeterRegistry registry;
    private AggregationSources sources;
    private SettlementConsistencyStep step;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        sources = new AggregationSources(billing, null, null);
        step = new SettlementConsistencyStep(sources, repository, new AggregationMetrics(registry));
    }

    private static OwnerCampaignLedgerSum ledger(Long campaign, long reserve, long deduct, long release,
            long positiveAdjust) {
        return new OwnerCampaignLedgerSum(1L, campaign, 0, reserve, deduct, release, 0, positiveAdjust, 0);
    }

    private void ledgers(OwnerCampaignLedgerSum... ledgers) {
        when(billing.ledgerSums(DAY)).thenReturn(List.of(ledgers));
    }

    private double violations(String check) {
        return registry.get("analytics_consistency_violations").tag("check", check).gauge().value();
    }

    @Test
    void 이름은_지표_라벨로_쓰는_단계_이름이다() {
        assertThat(step.name()).isEqualTo("SettlementConsistencyStep");
    }

    @Test
    void 예약_해제와_소진액이_모두_맞으면_위반이_없다() {
        ledgers(ledger(CAMPAIGN, -5000, -3000, 2000, 0));
        when(repository.find(DAY)).thenReturn(Optional.of(
                new SettlementAnalyticsRow(0, 3000, 5000, 2000, 0, 0, 0, 0)));

        SettlementFindings findings = step.collect(DAY);
        int rows = step.publish(DAY, findings);

        assertThat(rows).isZero();
        assertThat(violations("V-2")).isZero();
        assertThat(violations("V-3")).isZero();
        assertThat(violations("V-7")).isZero();
    }

    // 판정 BILLED 와 원장 DEDUCT 두 사본이 판정 하나가 되어 맞댈 것이 없다(#1249, #1293)
    @Test
    void BILLED_수와_원장_DEDUCT_를_맞대던_V1_은_더_기록하지_않는다() {
        ledgers(ledger(CAMPAIGN, -5000, -3000, 2000, 0));
        when(repository.find(DAY)).thenReturn(Optional.of(
                new SettlementAnalyticsRow(0, 3000, 5000, 2000, 0, 0, 0, 0)));

        step.publish(DAY, step.collect(DAY));

        assertThat(registry.find("analytics_consistency_violations").tag("check", "V-1").gauge()).isNull();
    }

    @Test
    void 차감이_예약을_넘으면_V2_위반이다() {
        ledgers(ledger(CAMPAIGN, -1000, -1500, 0, 0));
        when(repository.find(DAY)).thenReturn(Optional.of(
                new SettlementAnalyticsRow(0, 1500, 1000, 0, 0, 0, 0, 0)));

        step.publish(DAY, step.collect(DAY));

        assertThat(violations("V-2")).isEqualTo(1.0);
    }

    @Test
    void 해제가_예약에서_차감을_뺀_값이_아니면_V3_위반이다() {
        ledgers(ledger(CAMPAIGN, -5000, -3000, 0, 0));
        when(repository.find(DAY)).thenReturn(Optional.of(
                new SettlementAnalyticsRow(0, 3000, 5000, 0, 0, 0, 0, 0)));

        step.publish(DAY, step.collect(DAY));

        assertThat(violations("V-3")).isEqualTo(1.0);
        assertThat(violations("V-2")).isZero();
    }

    @Test
    void 캠페인_없는_양수_정정은_플랫폼_소진액에서만_빠진다() {
        ledgers(
                ledger(CAMPAIGN, -5000, -3000, 2000, 200),
                ledger(null, 0, 0, 0, 100));
        when(repository.find(DAY)).thenReturn(Optional.of(
                new SettlementAnalyticsRow(0, 3000, 5000, 2000, 0, 300, 300, 0)));

        SettlementFindings findings = step.collect(DAY);
        step.publish(DAY, findings);

        assertThat(findings.expectedSpent()).isEqualTo(2700);
        assertThat(violations("V-7")).isZero();
    }

    @Test
    void 저장된_소진액이_원장에서_다시_계산한_값과_다르면_V7_위반이다() {
        ledgers(ledger(CAMPAIGN, -5000, -3000, 2000, 0));
        when(repository.find(DAY)).thenReturn(Optional.of(
                new SettlementAnalyticsRow(0, 2900, 5000, 2000, 0, 0, 0, 0)));

        step.publish(DAY, step.collect(DAY));

        assertThat(violations("V-7")).isEqualTo(1.0);
    }

    @Test
    void 저장된_행이_없으면_V7_위반이다() {
        ledgers(ledger(CAMPAIGN, -5000, -3000, 2000, 0));
        when(repository.find(DAY)).thenReturn(Optional.empty());

        step.publish(DAY, step.collect(DAY));

        assertThat(violations("V-7")).isEqualTo(1.0);
    }

    @Test
    void 정정이_차감보다_커서_소진액이_음수여도_저장된_값과_맞으면_위반이_아니다() {
        ledgers(ledger(CAMPAIGN, -1000, -100, 900, 350));
        when(repository.find(DAY)).thenReturn(Optional.of(
                new SettlementAnalyticsRow(0, 100, 1000, 900, 0, 350, 350, 0)));

        SettlementFindings findings = step.collect(DAY);
        step.publish(DAY, findings);

        assertThat(findings.expectedSpent()).isEqualTo(-250);
        assertThat(violations("V-7")).isZero();
    }

    @Test
    void 그날_원장이_하나도_없으면_모든_검사가_위반_없이_지나간다() {
        when(billing.ledgerSums(DAY)).thenReturn(List.of());
        when(repository.find(DAY)).thenReturn(Optional.of(
                new SettlementAnalyticsRow(0, 0, 0, 0, 0, 0, 0, 0)));

        step.publish(DAY, step.collect(DAY));

        assertThat(violations("V-2") + violations("V-3") + violations("V-7")).isZero();
    }

    // 범위 안에서는 앞 단계가 읽은 목록을 나눠 쓴다. 나눠 쓴 결과가 단계마다 원천을 읽던 때와 같아야 한다(#1257)
    @Test
    void 범위_안에서_나눠_쓴_원천으로_모은_결과가_범위_밖에서_모은_결과와_같다() {
        ledgers(
                ledger(CAMPAIGN, -5000, -3000, 0, 200),
                ledger(null, 0, 0, 0, 100));

        SettlementFindings outside = step.collect(DAY);
        SettlementFindings first;
        SettlementFindings second;
        try (AggregationSources.Scope ignored = sources.open()) {
            first = step.collect(DAY);
            second = step.collect(DAY);
        }

        assertThat(first).isEqualTo(outside);
        assertThat(second).isEqualTo(outside);
        verify(billing, times(2)).ledgerSums(DAY);
    }
}
