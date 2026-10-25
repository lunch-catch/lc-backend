package com.launchcatch.analytics.batch.service.step;

import com.launchcatch.adserving.contract.AdservingStatsQueryService;
import com.launchcatch.adserving.contract.CampaignImpressionStats;
import com.launchcatch.analytics.batch.CampaignReport;
import com.launchcatch.analytics.batch.CampaignReport.SlotType;
import com.launchcatch.analytics.batch.InvalidReasonCode;
import com.launchcatch.analytics.batch.NotBilledReason;
import com.launchcatch.analytics.batch.repository.CampaignReportRepository;
import com.launchcatch.analytics.batch.service.AggregationMetrics;
import com.launchcatch.analytics.batch.service.AggregationStep;
import com.launchcatch.analytics.batch.service.SpendFormula;
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
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 캠페인 리포트의 일별 값을 모아 campaign_daily_report 와 자식 표(사유, 시간대, 슬롯)의 그날 행으로 교체한다(#625).
 *
 * 예산, 단가, 과금액, 정정액, 과금 판정은 BillingStatsQueryService, 노출과 사유와 시간대와 슬롯은
 * AdservingStatsQueryService, 발급과 사용은 CouponStatsQueryService 가 캠페인별로 세어 준다. 분석이 캠페인 ID 로 합친다.
 * 소진액은 매출(A-07)과 같은 SpendFormula 로 계산한다.
 *
 * 찜은 계약의 wishCount 가 아니라 event_log 의 wish 로 센다. 퍼널과 같은 이유다(AD-31).
 *
 * 점주와 가게는 CampaignQueryService.findAllById 로 읽어 행에 저장한다(#426, AD-28). 조회가 다른 도메인을 부르지 않고
 * 이 값으로 소유를 확인하기 때문이다. 찾지 못한 캠페인 ID 는 행을 만들지 않는다. 무효 노출은 요청이 보낸 캠페인 ID 라
 * 없는 캠페인일 수 있다.
 */
@Slf4j
@Component
@Order(600)
public class CampaignReportStep implements AggregationStep<CampaignReport> {

    static final String VALID_IMPRESSION_CHECK = "CAMPAIGN_REPORT_VALID_IMPRESSION";

    private static final int RATE_SCALE = 6;

    private final BillingStatsQueryService billing;
    private final AdservingStatsQueryService adserving;
    private final CouponStatsQueryService coupons;
    private final CampaignQueryService campaigns;
    private final EventLogRepository eventLog;
    private final CampaignReportRepository repository;
    private final AggregationMetrics metrics;
    private final Clock clock;

    public CampaignReportStep(
            BillingStatsQueryService billing,
            AdservingStatsQueryService adserving,
            CouponStatsQueryService coupons,
            CampaignQueryService campaigns,
            EventLogRepository eventLog,
            CampaignReportRepository repository,
            AggregationMetrics metrics,
            Clock clock) {
        this.billing = billing;
        this.adserving = adserving;
        this.coupons = coupons;
        this.campaigns = campaigns;
        this.eventLog = eventLog;
        this.repository = repository;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "CampaignReportStep";
    }

    @Override
    public CampaignReport collect(LocalDate businessDate) {
        Map<Long, CampaignDailyBudget> budgets = byCampaign(billing.dailyBudgets(businessDate),
                CampaignDailyBudget::campaignId);
        Map<Long, CampaignBillingCount> counts = byCampaign(billing.billingCounts(businessDate),
                CampaignBillingCount::campaignId);
        Map<Long, CampaignImpressionStats> impressions = byCampaign(adserving.campaignStats(businessDate),
                CampaignImpressionStats::campaignId);
        Map<Long, DailyCampaignCouponStats> couponStats = byCampaign(coupons.dailyCampaignStats(businessDate),
                DailyCampaignCouponStats::campaignId);
        Map<Long, long[]> ledger = ledgerByCampaign(billing.ledgerSums(businessDate));
        Map<Long, Long> wishes = eventLog.countWishesByCampaign(businessDate);

        Set<Long> campaignIds = new TreeSet<>();
        campaignIds.addAll(budgets.keySet());
        campaignIds.addAll(counts.keySet());
        campaignIds.addAll(impressions.keySet());
        campaignIds.addAll(couponStats.keySet());
        campaignIds.addAll(ledger.keySet());
        campaignIds.addAll(wishes.keySet());
        if (campaignIds.isEmpty()) {
            metrics.recordViolations(VALID_IMPRESSION_CHECK, 0);
            return new CampaignReport(List.of());
        }
        Map<Long, CampaignInfo> infos = byCampaign(campaigns.findAllById(List.copyOf(campaignIds)),
                CampaignInfo::campaignId);

        List<CampaignReport.Row> rows = new ArrayList<>();
        long mismatched = 0;
        int unknown = 0;
        for (long campaignId : campaignIds) {
            CampaignInfo info = infos.get(campaignId);
            if (info == null) {
                unknown++;
                continue;
            }
            CampaignReport.Row row = row(info, budgets.get(campaignId), counts.get(campaignId),
                    impressions.get(campaignId), couponStats.get(campaignId), ledger.get(campaignId),
                    wishes.getOrDefault(campaignId, 0L));
            CampaignImpressionStats served = impressions.get(campaignId);
            if (served != null && served.validCount() != row.validCount()) {
                mismatched++;
                log.warn("event=ANALYTICS_CAMPAIGN_REPORT_VALID_IMPRESSION_MISMATCH businessDate={} campaignId={} served={} judged={}",
                        businessDate, campaignId, served.validCount(), row.validCount());
            }
            rows.add(row);
        }
        metrics.recordViolations(VALID_IMPRESSION_CHECK, mismatched);
        if (unknown > 0) {
            log.info("event=ANALYTICS_CAMPAIGN_REPORT_UNKNOWN_CAMPAIGN businessDate={} skippedCount={}",
                    businessDate, unknown);
        }
        if (mismatched > 0) {
            log.error("event=ANALYTICS_CAMPAIGN_REPORT_DONE businessDate={} campaignCount={} mismatchCount={}",
                    businessDate, rows.size(), mismatched);
        }
        return new CampaignReport(rows);
    }

    /*
     * 덮어쓰기 전에 이미 확정된 행을 읽어 두고, 공개 지표가 바뀐 행만 변경 이력으로 남긴다(#632).
     * 공개 트랜잭션 안이라 읽기와 교체와 이력이 함께 커밋된다. 같은 날짜는 실행 행을 잡은 시도 하나만 공개한다.
     */
    @Override
    public int publish(LocalDate businessDate, CampaignReport collected) {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, CampaignReport.Finalized> finalized = repository.findFinalized(businessDate);
        repository.replace(businessDate, collected.rows(), now);
        List<CampaignReport.Revision> revisions = revisions(finalized, collected.rows());
        if (!revisions.isEmpty()) {
            repository.insertRevisions(revisions, now);
        }
        return collected.rows().size();
    }

    // 정정액이 바뀌었으면 과오 차감 정정이고, 그 밖의 변경은 늦게 확정된 원천이 반영된 것이다
    private List<CampaignReport.Revision> revisions(Map<Long, CampaignReport.Finalized> finalized,
            List<CampaignReport.Row> rows) {
        List<CampaignReport.Revision> revisions = new ArrayList<>();
        for (CampaignReport.Row row : rows) {
            CampaignReport.Finalized saved = finalized.get(row.campaignId());
            CampaignReport.Metrics after = row.metrics();
            if (saved == null || saved.metrics().equals(after)) {
                continue;
            }
            CampaignReport.ChangeReason reason = saved.metrics().refundedAmount() != after.refundedAmount()
                    ? CampaignReport.ChangeReason.OVERCHARGE_REVERSAL
                    : CampaignReport.ChangeReason.LATE_EVENT;
            revisions.add(new CampaignReport.Revision(saved.reportId(), saved.lastRevision() + 1, reason,
                    saved.metrics(), after));
        }
        return revisions;
    }

    /*
     * 유효 노출은 정산의 과금 판정과 쿠폰 소진 뒤 노출의 합으로 저장한다.
     * 표의 CHECK 가 유효 = 과금 + 미과금을 요구해서, 광고 서빙의 validCount 를 그대로 넣으면 원천끼리 어긋난 날 공개가
     * 거부되고 그날 집계 전체가 실패한다. 어긋남은 collect 가 지표와 로그로 남긴다.
     */
    private CampaignReport.Row row(CampaignInfo info, CampaignDailyBudget budget, CampaignBillingCount count,
            CampaignImpressionStats served, DailyCampaignCouponStats coupon, long[] ledger, long wishCount) {
        long dailyBudget = budget == null ? 0 : budget.dailyBudget();
        long charged = ledger == null ? 0 : -ledger[0];
        long refunded = ledger == null ? 0 : ledger[1];
        long spent = SpendFormula.spentAmount(charged, refunded);

        Map<NotBilledReason, Long> notBilled = new EnumMap<>(NotBilledReason.class);
        if (count != null) {
            putPositive(notBilled, NotBilledReason.NOT_BILLED_BUDGET, count.notBilledBudgetCount());
            putPositive(notBilled, NotBilledReason.NOT_BILLED_PAUSED, count.notBilledPausedCount());
            putPositive(notBilled, NotBilledReason.NOT_BILLED_NO_PLAN, count.notBilledNoPlanCount());
        }
        Map<InvalidReasonCode, Long> invalid = new EnumMap<>(InvalidReasonCode.class);
        Map<Integer, Long> hourly = new TreeMap<>();
        Map<SlotType, Long> slots = new EnumMap<>(SlotType.class);
        if (served != null) {
            putPositive(notBilled, NotBilledReason.NOT_BILLED_SOLD_OUT, served.soldOutCount());
            putPositive(invalid, InvalidReasonCode.EXPIRED_OR_UNKNOWN, served.expiredOrUnknownCount());
            putPositive(invalid, InvalidReasonCode.NOT_OWNER, served.notOwnerCount());
            for (int hour = 0; hour < served.hourlyCounts().size(); hour++) {
                putPositive(hourly, hour, served.hourlyCounts().get(hour));
            }
            putPositive(slots, SlotType.ALLOCATION, served.allocationCount());
            putPositive(slots, SlotType.RELEVANCE, served.relevanceCount());
        }
        long billable = count == null ? 0 : count.billedCount();
        long notBilledCount = notBilled.values().stream().mapToLong(Long::longValue).sum();

        return new CampaignReport.Row(
                info.campaignId(), info.ownerId(), info.storeId(),
                dailyBudget, budget == null ? 0 : budget.unitPrice(),
                charged, refunded, spent, spendRate(spent, dailyBudget),
                billable + notBilledCount, billable, notBilledCount,
                invalid.values().stream().mapToLong(Long::longValue).sum(),
                wishCount,
                coupon == null ? 0 : coupon.issuedCount(),
                coupon == null ? 0 : coupon.usedCount(),
                notBilled, invalid, hourly, slots);
    }

    // 하루 예산이 0 이면 0 으로 둔다. 조회가 예산 0 을 보고 null 로 내린다
    private BigDecimal spendRate(long spent, long dailyBudget) {
        if (dailyBudget == 0) {
            return BigDecimal.ZERO.setScale(RATE_SCALE);
        }
        return BigDecimal.valueOf(spent).divide(BigDecimal.valueOf(dailyBudget), RATE_SCALE, RoundingMode.HALF_UP);
    }

    // 캠페인마다 [DEDUCT 합(0 이하), 양수 ADJUST 합] 이다. 캠페인 없는 행은 플랫폼 합계(A-07)에만 들어가 뺀다
    private Map<Long, long[]> ledgerByCampaign(List<OwnerCampaignLedgerSum> sums) {
        Map<Long, long[]> byCampaign = new HashMap<>();
        for (OwnerCampaignLedgerSum sum : sums) {
            if (sum.campaignId() == null) {
                continue;
            }
            long[] amounts = byCampaign.computeIfAbsent(sum.campaignId(), id -> new long[2]);
            amounts[0] += sum.deductAmount();
            amounts[1] += sum.positiveAdjustAmount();
        }
        return byCampaign;
    }

    // 원천은 캠페인마다 한 행을 준다. 같은 캠페인이 두 번 오면 원천의 결함이라 그날 집계를 실패시켜 드러낸다
    private static <T> Map<Long, T> byCampaign(List<T> values, Function<T, Long> campaignId) {
        return values.stream().collect(Collectors.toMap(campaignId, Function.identity()));
    }

    private static <K> void putPositive(Map<K, Long> target, K key, long count) {
        if (count > 0) {
            target.put(key, count);
        }
    }
}
