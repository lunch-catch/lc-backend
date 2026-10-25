package com.launchcatch.analytics.batch.service.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.contract.AdservingStatsQueryService;
import com.launchcatch.adserving.contract.CampaignImpressionStats;
import com.launchcatch.analytics.batch.CampaignReport;
import com.launchcatch.analytics.batch.CampaignReport.SlotType;
import com.launchcatch.analytics.batch.InvalidReasonCode;
import com.launchcatch.analytics.batch.NotBilledReason;
import com.launchcatch.analytics.batch.repository.CampaignReportRepository;
import com.launchcatch.analytics.batch.service.AggregationMetrics;
import com.launchcatch.analytics.event.repository.EventLogRepository;
import com.launchcatch.billing.contract.BillingStatsQueryService;
import com.launchcatch.billing.contract.CampaignBillingCount;
import com.launchcatch.billing.contract.CampaignDailyBudget;
import com.launchcatch.billing.contract.OwnerCampaignLedgerSum;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.coupon.contract.CouponStatsQueryService;
import com.launchcatch.coupon.contract.DailyCampaignCouponStats;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/*
 * 캠페인 리포트 한 행이 정산, 광고 서빙, 쿠폰, event_log, 캠페인 연결 정보를 캠페인 ID 로 합친 값인지 본다(#625).
 *
 * 여기서 막는 사고는 점주가 보는 소진액과 노출 수가 원천과 어긋나는 것이다. 소진액은 매출(A-07)과 같은 식을 쓰고,
 * 유효 노출은 표의 CHECK(유효 = 과금 + 미과금)를 지켜야 하루 집계 전체가 실패하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class CampaignReportStepTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T16:00:00Z"),
            ZoneId.of("Asia/Seoul"));
    private static final long CAMPAIGN = 12L;
    private static final long OWNER = 7L;
    private static final long STORE = 3L;

    @Mock
    private BillingStatsQueryService billing;
    @Mock
    private AdservingStatsQueryService adserving;
    @Mock
    private CouponStatsQueryService coupons;
    @Mock
    private CampaignQueryService campaigns;
    @Mock
    private EventLogRepository eventLog;
    @Mock
    private CampaignReportRepository repository;
    @Mock
    private AggregationMetrics metrics;

    private CampaignReportStep step() {
        return new CampaignReportStep(billing, adserving, coupons, campaigns, eventLog, repository, metrics, CLOCK);
    }

    private static List<Long> hourly(Map<Integer, Long> counts) {
        List<Long> hours = new ArrayList<>(Collections.nCopies(24, 0L));
        counts.forEach(hours::set);
        return hours;
    }

    private static CampaignInfo campaign(long campaignId, long ownerId, long storeId) {
        CampaignInfo info = mock(CampaignInfo.class);
        when(info.campaignId()).thenReturn(campaignId);
        when(info.ownerId()).thenReturn(ownerId);
        when(info.storeId()).thenReturn(storeId);
        return info;
    }

    private static OwnerCampaignLedgerSum ledger(Long campaignId, long deduct, long positiveAdjust) {
        return new OwnerCampaignLedgerSum(OWNER, campaignId, 0, 0, deduct, 0, 0, positiveAdjust, 0);
    }

    private void noSources() {
        when(billing.ledgerSums(DAY)).thenReturn(List.of());
        when(billing.billingCounts(DAY)).thenReturn(List.of());
        when(billing.dailyBudgets(DAY)).thenReturn(List.of());
        when(adserving.campaignStats(DAY)).thenReturn(List.of());
        when(coupons.dailyCampaignStats(DAY)).thenReturn(List.of());
        when(eventLog.countWishesByCampaign(DAY)).thenReturn(Map.of());
    }

    private CampaignReport.Row only(CampaignReport report) {
        assertThat(report.rows()).hasSize(1);
        return report.rows().get(0);
    }

    @Test
    void 이름은_지표_라벨로_쓰는_단계_이름이다() {
        assertThat(step().name()).isEqualTo("CampaignReportStep");
    }

    @Test
    void 정산_광고_서빙_쿠폰_찜_캠페인_연결_정보를_캠페인마다_한_행으로_합친다() {
        noSources();
        when(billing.dailyBudgets(DAY)).thenReturn(List.of(new CampaignDailyBudget(CAMPAIGN, 10_000, 2)));
        when(billing.ledgerSums(DAY)).thenReturn(List.of(ledger(CAMPAIGN, -8_400, 600)));
        when(billing.billingCounts(DAY)).thenReturn(List.of(new CampaignBillingCount(CAMPAIGN, 4_200, 180, 80, 10)));
        when(adserving.campaignStats(DAY)).thenReturn(List.of(new CampaignImpressionStats(
                CAMPAIGN, 4_510, 40, 12, 8, hourly(Map.of(10, 1_500L, 23, 3_010L)), 1_350, 3_160, 999)));
        when(coupons.dailyCampaignStats(DAY)).thenReturn(List.of(new DailyCampaignCouponStats(CAMPAIGN, 0, 190, 76, 0)));
        when(eventLog.countWishesByCampaign(DAY)).thenReturn(Map.of(CAMPAIGN, 380L));
        List<CampaignInfo> infos = List.of(campaign(CAMPAIGN, OWNER, STORE));
        when(campaigns.findAllById(List.of(CAMPAIGN))).thenReturn(infos);

        CampaignReport.Row row = only(step().collect(DAY));

        assertThat(row.campaignId()).isEqualTo(CAMPAIGN);
        assertThat(row.ownerId()).isEqualTo(OWNER);
        assertThat(row.storeId()).isEqualTo(STORE);
        assertThat(row.dailyBudget()).isEqualTo(10_000);
        assertThat(row.unitPrice()).isEqualTo(2);
        assertThat(row.chargedAmount()).isEqualTo(8_400);
        assertThat(row.refundedAmount()).isEqualTo(600);
        assertThat(row.spentAmount()).isEqualTo(7_800);
        assertThat(row.budgetSpendRate()).isEqualByComparingTo(new BigDecimal("0.780000"));
        assertThat(row.validCount()).isEqualTo(4_510);
        assertThat(row.billableCount()).isEqualTo(4_200);
        assertThat(row.notBilledCount()).isEqualTo(310);
        assertThat(row.invalidCount()).isEqualTo(20);
        assertThat(row.wishCount()).isEqualTo(380);
        assertThat(row.issuedCount()).isEqualTo(190);
        assertThat(row.redeemCount()).isEqualTo(76);
        assertThat(row.notBilledReasons()).containsExactlyInAnyOrderEntriesOf(Map.of(
                NotBilledReason.NOT_BILLED_BUDGET, 180L, NotBilledReason.NOT_BILLED_PAUSED, 80L,
                NotBilledReason.NOT_BILLED_NO_PLAN, 10L, NotBilledReason.NOT_BILLED_SOLD_OUT, 40L));
        assertThat(row.invalidReasons()).containsExactlyInAnyOrderEntriesOf(Map.of(
                InvalidReasonCode.EXPIRED_OR_UNKNOWN, 12L, InvalidReasonCode.NOT_OWNER, 8L));
        assertThat(row.hourlyCounts()).containsExactlyInAnyOrderEntriesOf(Map.of(10, 1_500L, 23, 3_010L));
        assertThat(row.slotCounts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                SlotType.ALLOCATION, 1_350L, SlotType.RELEVANCE, 3_160L));
    }

    /*
     * 계약의 wishCount 는 31일만 보관하는 swipe_log 가 원천이라 31일이 지난 날짜를 다시 집계하면 0 이다.
     * event_log 로 세야 재집계가 저장된 찜 수를 0 으로 덮지 않는다(AD-31).
     */
    @Test
    void 찜_수는_계약의_wishCount_가_아니라_event_log_의_wish_로_센다() {
        noSources();
        when(adserving.campaignStats(DAY)).thenReturn(List.of(new CampaignImpressionStats(
                CAMPAIGN, 0, 0, 0, 0, hourly(Map.of()), 0, 0, 999)));
        List<CampaignInfo> infos = List.of(campaign(CAMPAIGN, OWNER, STORE));
        when(campaigns.findAllById(List.of(CAMPAIGN))).thenReturn(infos);

        assertThat(only(step().collect(DAY)).wishCount()).isZero();
    }

    @Test
    void 원천_하나에만_있는_캠페인도_나머지를_0_으로_채워_행을_만든다() {
        noSources();
        when(coupons.dailyCampaignStats(DAY)).thenReturn(List.of(new DailyCampaignCouponStats(CAMPAIGN, 0, 3, 1, 0)));
        List<CampaignInfo> infos = List.of(campaign(CAMPAIGN, OWNER, STORE));
        when(campaigns.findAllById(List.of(CAMPAIGN))).thenReturn(infos);

        CampaignReport.Row row = only(step().collect(DAY));

        assertThat(row.issuedCount()).isEqualTo(3);
        assertThat(row.validCount()).isZero();
        assertThat(row.spentAmount()).isZero();
        assertThat(row.notBilledReasons()).isEmpty();
        assertThat(row.hourlyCounts()).isEmpty();
        assertThat(row.slotCounts()).isEmpty();
    }

    /*
     * EXPIRED_OR_UNKNOWN 은 요청이 보낸 캠페인 ID 라 없는 캠페인일 수 있다.
     * 점주를 모르는 행을 만들면 어느 점주에게도 보이지 않는 리포트가 남는다.
     */
    @Test
    void 캠페인_연결_정보를_찾지_못한_캠페인은_행을_만들지_않는다() {
        noSources();
        when(adserving.campaignStats(DAY)).thenReturn(List.of(
                new CampaignImpressionStats(CAMPAIGN, 0, 0, 1, 0, hourly(Map.of()), 0, 0, 0),
                new CampaignImpressionStats(99L, 0, 0, 5, 0, hourly(Map.of()), 0, 0, 0)));
        List<CampaignInfo> infos = List.of(campaign(CAMPAIGN, OWNER, STORE));
        when(campaigns.findAllById(List.of(CAMPAIGN, 99L))).thenReturn(infos);

        assertThat(only(step().collect(DAY)).campaignId()).isEqualTo(CAMPAIGN);
    }

    @Test
    void 정정이_차감보다_크면_소진액은_음수이고_0_으로_자르지_않는다() {
        noSources();
        when(billing.ledgerSums(DAY)).thenReturn(List.of(ledger(CAMPAIGN, -100, 300)));
        when(billing.dailyBudgets(DAY)).thenReturn(List.of(new CampaignDailyBudget(CAMPAIGN, 1_000, 2)));
        List<CampaignInfo> infos = List.of(campaign(CAMPAIGN, OWNER, STORE));
        when(campaigns.findAllById(List.of(CAMPAIGN))).thenReturn(infos);

        CampaignReport.Row row = only(step().collect(DAY));

        assertThat(row.spentAmount()).isEqualTo(-200);
        assertThat(row.budgetSpendRate()).isEqualByComparingTo(new BigDecimal("-0.200000"));
    }

    @Test
    void 하루_예산이_0_이면_소진율은_0_이다() {
        noSources();
        when(billing.ledgerSums(DAY)).thenReturn(List.of(ledger(CAMPAIGN, -100, 0)));
        List<CampaignInfo> infos = List.of(campaign(CAMPAIGN, OWNER, STORE));
        when(campaigns.findAllById(List.of(CAMPAIGN))).thenReturn(infos);

        assertThat(only(step().collect(DAY)).budgetSpendRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // 캠페인 없는 조정은 플랫폼 합계(A-07)에만 들어간다
    @Test
    void 캠페인_없는_원장_합계는_리포트에_넣지_않는다() {
        noSources();
        when(billing.ledgerSums(DAY)).thenReturn(List.of(ledger(null, 0, 500)));

        assertThat(step().collect(DAY).rows()).isEmpty();
        verify(campaigns, never()).findAllById(anyList());
    }

    /*
     * 표의 CHECK 가 유효 = 과금 + 미과금을 요구한다. 광고 서빙의 validCount 를 그대로 넣으면 원천끼리 어긋난 날 공개가
     * 거부되어 그날 집계 전체가 실패한다. 그래서 정산 판정과 쿠폰 소진 뒤 노출의 합으로 저장하고 어긋난 캠페인 수를 지표로 남긴다.
     */
    @Test
    void 광고_서빙의_유효_노출이_정산_판정_합과_어긋나면_판정_합으로_저장하고_위반을_남긴다() {
        noSources();
        when(billing.billingCounts(DAY)).thenReturn(List.of(new CampaignBillingCount(CAMPAIGN, 100, 0, 0, 0)));
        when(adserving.campaignStats(DAY)).thenReturn(List.of(new CampaignImpressionStats(
                CAMPAIGN, 103, 2, 0, 0, hourly(Map.of(12, 103L)), 103, 0, 0)));
        List<CampaignInfo> infos = List.of(campaign(CAMPAIGN, OWNER, STORE));
        when(campaigns.findAllById(List.of(CAMPAIGN))).thenReturn(infos);

        CampaignReport.Row row = only(step().collect(DAY));

        assertThat(row.validCount()).isEqualTo(102);
        assertThat(row.billableCount() + row.notBilledCount()).isEqualTo(row.validCount());
        verify(metrics).recordViolations("CAMPAIGN_REPORT_VALID_IMPRESSION", 1);
    }

    @Test
    void 유효_노출이_맞으면_위반을_0_으로_남긴다() {
        noSources();
        when(billing.billingCounts(DAY)).thenReturn(List.of(new CampaignBillingCount(CAMPAIGN, 100, 0, 0, 0)));
        when(adserving.campaignStats(DAY)).thenReturn(List.of(new CampaignImpressionStats(
                CAMPAIGN, 102, 2, 0, 0, hourly(Map.of(12, 102L)), 102, 0, 0)));
        List<CampaignInfo> infos = List.of(campaign(CAMPAIGN, OWNER, STORE));
        when(campaigns.findAllById(List.of(CAMPAIGN))).thenReturn(infos);

        step().collect(DAY);

        verify(metrics).recordViolations("CAMPAIGN_REPORT_VALID_IMPRESSION", 0);
    }

    @Test
    void 공개는_그날_리포트를_교체하고_쓴_캠페인_수를_돌려준다() {
        CampaignReport.Row row = new CampaignReport.Row(CAMPAIGN, OWNER, STORE, 0, 0, 0, 0, 0, BigDecimal.ZERO,
                0, 0, 0, 0, 0, 0, 0, Map.of(), Map.of(), Map.of(), Map.of());

        int written = step().publish(DAY, new CampaignReport(List.of(row)));

        assertThat(written).isEqualTo(1);
        verify(repository).replace(DAY, List.of(row), LocalDateTime.now(CLOCK));
    }

    /*
     * 확정 뒤 다시 집계돼 공개 지표가 바뀌면 변경 전 값을 이력으로 남긴다(#632).
     * 남기지 않으면 덮어쓴 값은 복원할 수 없다.
     */
    @Test
    void 확정된_리포트의_공개_지표가_바뀌면_변경_전후와_다음_차수를_이력으로_남긴다() {
        CampaignReport.Row before = reportRow(4, 0, 2);
        CampaignReport.Row after = reportRow(6, 0, 3);
        when(repository.findFinalized(DAY)).thenReturn(Map.of(CAMPAIGN,
                new CampaignReport.Finalized(55L, 2, before.metrics())));

        step().publish(DAY, new CampaignReport(List.of(after)));

        verify(repository).insertRevisions(List.of(new CampaignReport.Revision(55L, 3,
                CampaignReport.ChangeReason.LATE_EVENT, before.metrics(), after.metrics())), LocalDateTime.now(CLOCK));
    }

    @Test
    void 정정액이_바뀌면_사유는_과오_차감_정정이다() {
        CampaignReport.Row before = reportRow(4, 0, 2);
        CampaignReport.Row after = reportRow(4, 2, 2);
        when(repository.findFinalized(DAY)).thenReturn(Map.of(CAMPAIGN,
                new CampaignReport.Finalized(55L, 0, before.metrics())));

        step().publish(DAY, new CampaignReport(List.of(after)));

        verify(repository).insertRevisions(List.of(new CampaignReport.Revision(55L, 1,
                CampaignReport.ChangeReason.OVERCHARGE_REVERSAL, before.metrics(), after.metrics())),
                LocalDateTime.now(CLOCK));
    }

    // 소진율은 저장된 값과 계산한 값의 자릿수가 달라도 같은 값이면 바뀐 것으로 보지 않는다
    @Test
    void 공개_지표가_같으면_이력을_남기지_않는다() {
        CampaignReport.Row row = reportRow(4, 0, 2);
        CampaignReport.Metrics stored = new CampaignReport.Row(CAMPAIGN, OWNER, STORE, 1_000, 2, 4, 0, 4,
                new BigDecimal("0.004"), 2, 2, 0, 0, 0, 0, 0, Map.of(), Map.of(), Map.of(), Map.of()).metrics();
        when(repository.findFinalized(DAY)).thenReturn(Map.of(CAMPAIGN, new CampaignReport.Finalized(55L, 1, stored)));

        step().publish(DAY, new CampaignReport(List.of(row)));

        verify(repository, never()).insertRevisions(anyList(), any());
    }

    @Test
    void 처음_확정하는_리포트는_이력을_남기지_않는다() {
        when(repository.findFinalized(DAY)).thenReturn(Map.of());

        step().publish(DAY, new CampaignReport(List.of(reportRow(4, 0, 2))));

        verify(repository, never()).insertRevisions(anyList(), any());
    }

    // 하루 예산 1,000, 단가 2 인 캠페인의 과금액, 정정액, 과금 노출이 다른 행이다
    private static CampaignReport.Row reportRow(long charged, long refunded, long billable) {
        long spent = charged - refunded;
        return new CampaignReport.Row(CAMPAIGN, OWNER, STORE, 1_000, 2, charged, refunded, spent,
                BigDecimal.valueOf(spent).divide(BigDecimal.valueOf(1_000)).setScale(6), billable, billable, 0, 0, 0,
                0, 0, Map.of(), Map.of(), Map.of(), Map.of());
    }
}
