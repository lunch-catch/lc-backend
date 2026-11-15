package com.lunchcatch.analytics.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.adserving.contract.AdservingStatsQueryService;
import com.lunchcatch.billing.contract.BillingStatsQueryService;
import com.lunchcatch.coupon.contract.CouponStatsQueryService;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AggregationSourcesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);

    @Mock
    private BillingStatsQueryService billing;
    @Mock
    private AdservingStatsQueryService adserving;
    @Mock
    private CouponStatsQueryService coupons;

    private AggregationSources sources() {
        return new AggregationSources(billing, adserving, coupons);
    }

    @Test
    void 범위_밖에서는_부를_때마다_원천을_읽는다() {
        AggregationSources sources = sources();

        sources.ledgerSums(DAY);
        sources.ledgerSums(DAY);

        verify(billing, times(2)).ledgerSums(DAY);
    }

    @Test
    void 범위_안에서는_조회와_날짜마다_처음_한_번만_읽고_같은_결과를_돌려준다() {
        AggregationSources sources = sources();
        when(billing.billingCounts(DAY)).thenReturn(List.of());

        try (AggregationSources.Scope ignored = sources.open()) {
            assertThat(sources.billingCounts(DAY)).isSameAs(sources.billingCounts(DAY));
            sources.dailyBudgets(DAY);
            sources.dailyBudgets(DAY);
            sources.ledgerSums(DAY);
            sources.ledgerSums(DAY.minusDays(1));
            sources.campaignStats(DAY);
            sources.campaignStats(DAY);
            sources.dailyCampaignStats(DAY);
            sources.dailyCampaignStats(DAY);
        }

        verify(billing).billingCounts(DAY);
        verify(billing).dailyBudgets(DAY);
        verify(billing).ledgerSums(DAY);
        verify(billing).ledgerSums(DAY.minusDays(1));
        verify(adserving).campaignStats(DAY);
        verify(coupons).dailyCampaignStats(DAY);
    }

    // 재시도는 범위를 새로 열어 모으기부터 다시 하므로 앞 시도가 읽은 결과를 쓰지 않는다
    @Test
    void 범위를_닫고_다시_열면_새로_읽는다() {
        AggregationSources sources = sources();

        try (AggregationSources.Scope ignored = sources.open()) {
            sources.campaignStats(DAY);
        }
        try (AggregationSources.Scope ignored = sources.open()) {
            sources.campaignStats(DAY);
        }
        sources.campaignStats(DAY);

        verify(adserving, times(3)).campaignStats(DAY);
    }

    @Test
    void 읽기가_실패하면_결과를_남기지_않아_다음에_부를_때_다시_읽는다() {
        AggregationSources sources = sources();
        when(billing.ledgerSums(DAY)).thenThrow(new IllegalStateException("조회 실패")).thenReturn(List.of());

        try (AggregationSources.Scope ignored = sources.open()) {
            assertThatThrownBy(() -> sources.ledgerSums(DAY)).isInstanceOf(IllegalStateException.class);
            assertThat(sources.ledgerSums(DAY)).isEmpty();
        }

        verify(billing, times(2)).ledgerSums(DAY);
    }

    @Test
    void 범위는_연_스레드에만_걸린다() {
        AggregationSources sources = sources();

        try (AggregationSources.Scope ignored = sources.open()) {
            sources.campaignStats(DAY);
            CompletableFuture.runAsync(() -> sources.campaignStats(DAY)).join();
            sources.campaignStats(DAY);
        }

        verify(adserving, times(2)).campaignStats(DAY);
    }
}
