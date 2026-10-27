package com.lunchcatch.analytics.batch.service.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lunchcatch.analytics.batch.SettlementFindings;
import com.lunchcatch.analytics.batch.repository.SettlementAnalyticsRepository;
import com.lunchcatch.analytics.batch.repository.SettlementAnalyticsRow;
import com.lunchcatch.analytics.batch.service.AggregationMetrics;
import com.lunchcatch.billing.contract.BillingStatsQueryService;
import com.lunchcatch.billing.contract.CampaignBillingCount;
import com.lunchcatch.billing.contract.CampaignDailyBudget;
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
    private SettlementConsistencyStep step;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        step = new SettlementConsistencyStep(billing, repository, new AggregationMetrics(registry));
    }

    private static OwnerCampaignLedgerSum ledger(Long campaign, long reserve, long deduct, long release,
            long positiveAdjust) {
        return new OwnerCampaignLedgerSum(1L, campaign, 0, reserve, deduct, release, 0, positiveAdjust, 0);
    }

    private void campaign(long billed, Long unitPrice, OwnerCampaignLedgerSum... ledgers) {
        when(billing.ledgerSums(DAY)).thenReturn(List.of(ledgers));
        when(billing.billingCounts(DAY)).thenReturn(List.of(new CampaignBillingCount(CAMPAIGN, billed, 0, 0, 0)));
        when(billing.dailyBudgets(DAY)).thenReturn(unitPrice == null
                ? List.of() : List.of(new CampaignDailyBudget(CAMPAIGN, 10_000, unitPrice)));
    }

    private double violations(String check) {
        return registry.get("analytics_consistency_violations").tag("check", check).gauge().value();
    }

    @Test
    void 이름은_지표_라벨로_쓰는_단계_이름이다() {
        assertThat(step.name()).isEqualTo("SettlementConsistencyStep");
    }

    @Test
    void 과금_수와_단가와_예약_해제가_모두_맞으면_위반이_없다() {
        campaign(30, 100L, ledger(CAMPAIGN, -5000, -3000, 2000, 0));
        when(repository.find(DAY)).thenReturn(Optional.of(
                new SettlementAnalyticsRow(0, 3000, 5000, 2000, 0, 0, 0, 0)));

        SettlementFindings findings = step.collect(DAY);
        int rows = step.publish(DAY, findings);

        assertThat(rows).isZero();
        assertThat(violations("V-1")).isZero();
        assertThat(violations("V-2")).isZero();
        assertThat(violations("V-3")).isZero();
        assertThat(violations("V-7")).isZero();
    }

    @Test
    void 과금_수_곱하기_단가가_차감_합과_다르면_V1_위반이다() {
        campaign(29, 100L, ledger(CAMPAIGN, -5000, -3000, 2000, 0));
        when(repository.find(DAY)).thenReturn(Optional.of(
                new SettlementAnalyticsRow(0, 3000, 5000, 2000, 0, 0, 0, 0)));

        step.publish(DAY, step.collect(DAY));

        assertThat(violations("V-1")).isEqualTo(1.0);
        assertThat(violations("V-2")).isZero();
        assertThat(violations("V-3")).isZero();
    }

    @Test
    void 단가가_없는_캠페인에_차감이_있으면_V1_위반이다() {
        campaign(0, null, ledger(CAMPAIGN, -5000, -3000, 2000, 0));
        when(repository.find(DAY)).thenReturn(Optional.of(
                new SettlementAnalyticsRow(0, 3000, 5000, 2000, 0, 0, 0, 0)));

        step.publish(DAY, step.collect(DAY));

        assertThat(violations("V-1")).isEqualTo(1.0);
    }

    @Test
    void 차감이_예약을_넘으면_V2_위반이다() {
        campaign(15, 100L, ledger(CAMPAIGN, -1000, -1500, 0, 0));
        when(repository.find(DAY)).thenReturn(Optional.of(
                new SettlementAnalyticsRow(0, 1500, 1000, 0, 0, 0, 0, 0)));

        step.publish(DAY, step.collect(DAY));

        assertThat(violations("V-2")).isEqualTo(1.0);
    }

    @Test
    void 해제가_예약에서_차감을_뺀_값이_아니면_V3_위반이다() {
        campaign(30, 100L, ledger(CAMPAIGN, -5000, -3000, 0, 0));
        when(repository.find(DAY)).thenReturn(Optional.of(
                new SettlementAnalyticsRow(0, 3000, 5000, 0, 0, 0, 0, 0)));

        step.publish(DAY, step.collect(DAY));

        assertThat(violations("V-3")).isEqualTo(1.0);
        assertThat(violations("V-2")).isZero();
    }

    @Test
    void 캠페인_없는_양수_정정은_플랫폼_소진액에서만_빠진다() {
        campaign(30, 100L,
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
        campaign(30, 100L, ledger(CAMPAIGN, -5000, -3000, 2000, 0));
        when(repository.find(DAY)).thenReturn(Optional.of(
                new SettlementAnalyticsRow(0, 2900, 5000, 2000, 0, 0, 0, 0)));

        step.publish(DAY, step.collect(DAY));

        assertThat(violations("V-7")).isEqualTo(1.0);
    }

    @Test
    void 저장된_행이_없으면_V7_위반이다() {
        campaign(30, 100L, ledger(CAMPAIGN, -5000, -3000, 2000, 0));
        when(repository.find(DAY)).thenReturn(Optional.empty());

        step.publish(DAY, step.collect(DAY));

        assertThat(violations("V-7")).isEqualTo(1.0);
    }

    @Test
    void 정정이_차감보다_커서_소진액이_음수여도_저장된_값과_맞으면_위반이_아니다() {
        campaign(1, 100L, ledger(CAMPAIGN, -1000, -100, 900, 350));
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
        when(billing.billingCounts(DAY)).thenReturn(List.of());
        when(billing.dailyBudgets(DAY)).thenReturn(List.of());
        when(repository.find(DAY)).thenReturn(Optional.of(
                new SettlementAnalyticsRow(0, 0, 0, 0, 0, 0, 0, 0)));

        step.publish(DAY, step.collect(DAY));

        assertThat(violations("V-1") + violations("V-2") + violations("V-3") + violations("V-7")).isZero();
    }
}
