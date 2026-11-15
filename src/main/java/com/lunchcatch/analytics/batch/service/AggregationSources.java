package com.lunchcatch.analytics.batch.service;

import com.lunchcatch.adserving.contract.AdservingStatsQueryService;
import com.lunchcatch.adserving.contract.CampaignImpressionStats;
import com.lunchcatch.billing.contract.BillingStatsQueryService;
import com.lunchcatch.billing.contract.CampaignBillingCount;
import com.lunchcatch.billing.contract.CampaignDailyBudget;
import com.lunchcatch.billing.contract.OwnerCampaignLedgerSum;
import com.lunchcatch.coupon.contract.CouponStatsQueryService;
import com.lunchcatch.coupon.contract.DailyCampaignCouponStats;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/*
 * 일별 집계의 단계 여럿이 함께 쓰는 원천 집계 조회다(#1257). 같은 날짜의 같은 조회를 단계마다 다시 부르면
 * 하루치 노출과 원장을 그 횟수만큼 다시 읽는다.
 *
 * 실행기가 한 시도의 모으기 앞뒤로 범위를 열고 닫는다. 범위 안에서는 (조회, 날짜)마다 처음 한 번만 읽고 그
 * 결과를 단계들이 나눠 쓴다. 범위 밖에서는 부를 때마다 원천을 읽는다. 재시도는 범위를 새로 열어 모으기부터
 * 다시 하므로 앞 시도가 읽은 결과를 쓰지 않는다.
 *
 * 범위는 스레드마다 따로다. 같은 서버에서 실행권을 잃은 시도와 새 시도가 같은 날짜를 함께 돌아도 서로의
 * 결과를 쓰지 않는다. 단계는 받은 목록을 고치지 않는다. 같은 목록을 다른 단계가 읽는다.
 */
@Component
public class AggregationSources {

    private static final ThreadLocal<Map<ReadKey, Object>> READS = new ThreadLocal<>();

    private final BillingStatsQueryService billing;
    private final AdservingStatsQueryService adserving;
    private final CouponStatsQueryService coupons;

    public AggregationSources(
            BillingStatsQueryService billing,
            AdservingStatsQueryService adserving,
            CouponStatsQueryService coupons) {
        this.billing = billing;
        this.adserving = adserving;
        this.coupons = coupons;
    }

    // 한 시도의 모으기 동안 읽은 결과를 나눠 쓰는 범위를 연다. 닫으면 그 결과를 버린다
    public Scope open() {
        READS.set(new HashMap<>());
        return READS::remove;
    }

    public List<OwnerCampaignLedgerSum> ledgerSums(LocalDate businessDate) {
        return read("ledgerSums", businessDate, () -> billing.ledgerSums(businessDate));
    }

    public List<CampaignBillingCount> billingCounts(LocalDate businessDate) {
        return read("billingCounts", businessDate, () -> billing.billingCounts(businessDate));
    }

    public List<CampaignDailyBudget> dailyBudgets(LocalDate businessDate) {
        return read("dailyBudgets", businessDate, () -> billing.dailyBudgets(businessDate));
    }

    public List<CampaignImpressionStats> campaignStats(LocalDate businessDate) {
        return read("campaignStats", businessDate, () -> adserving.campaignStats(businessDate));
    }

    public List<DailyCampaignCouponStats> dailyCampaignStats(LocalDate businessDate) {
        return read("dailyCampaignStats", businessDate, () -> coupons.dailyCampaignStats(businessDate));
    }

    // 읽기가 실패하면 computeIfAbsent 가 아무것도 남기지 않아 다음에 부를 때 다시 읽는다
    @SuppressWarnings("unchecked")
    private <T> T read(String query, LocalDate businessDate, Supplier<T> source) {
        Map<ReadKey, Object> reads = READS.get();
        if (reads == null) {
            return source.get();
        }
        return (T) reads.computeIfAbsent(new ReadKey(query, businessDate), key -> source.get());
    }

    // try-with-resources 로 닫는다. 닫는 일은 예외를 던지지 않는다
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }

    private record ReadKey(String query, LocalDate businessDate) {
    }
}
