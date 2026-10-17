package com.launchcatch.analytics.query.service;

import com.launchcatch.analytics.query.AggregationStatus;
import com.launchcatch.analytics.query.AnalyticsPeriod;
import com.launchcatch.analytics.query.StoreMetricsBucket;
import com.launchcatch.analytics.query.StoreMetricsSeries;
import com.launchcatch.analytics.query.TimeSeriesBucketState;
import com.launchcatch.analytics.query.TimeSeriesState;
import com.launchcatch.analytics.query.repository.StoreMetricsDailyRow;
import com.launchcatch.analytics.query.repository.StoreMetricsQueryRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/*
 * A-02 가게 지표다. 일별 가게 지표를 일, 주, 월로 묶는다.
 *
 * 신규 입점은 흐름이라 합계, 누적 입점과 집행 중 캠페인은 스냅샷이라 묶음 마지막 날의 값이다. 일부만
 * 걸친 주와 월은 상태 서비스가 요청 범위로 잘라 준 시작과 끝을 쓴다.
 *
 * 집계가 끝나지 않은 구간(IN_PROGRESS)은 수치를 null 로 내린다.
 */
@Service
public class StoreMetricsQueryService {

    private final TimeSeriesStatusService statusService;
    private final StoreMetricsQueryRepository repository;

    public StoreMetricsQueryService(TimeSeriesStatusService statusService, StoreMetricsQueryRepository repository) {
        this.statusService = statusService;
        this.repository = repository;
    }

    public StoreMetricsSeries query(AnalyticsPeriod period, LocalDate from, LocalDate to) {
        TimeSeriesState state = statusService.resolve(period, from, to);
        List<StoreMetricsDailyRow> rows = repository.findBetween(from, to);
        List<StoreMetricsBucket> items = state.items().stream()
                .map(bucket -> toBucket(bucket, rows))
                .toList();
        return new StoreMetricsSeries(period, from, to, state.aggregatedAt(), items);
    }

    private StoreMetricsBucket toBucket(TimeSeriesBucketState bucket, List<StoreMetricsDailyRow> rows) {
        if (bucket.status() != AggregationStatus.FINAL) {
            return new StoreMetricsBucket(bucket.label(), bucket.status(), null, null, null);
        }
        long added = 0;
        StoreMetricsDailyRow last = null;
        for (StoreMetricsDailyRow row : rows) {
            if (row.businessDate().isBefore(bucket.start()) || row.businessDate().isAfter(bucket.end())) {
                continue;
            }
            added += row.newStoreCount();
            if (row.businessDate().equals(bucket.end())) {
                last = row;
            }
        }
        return new StoreMetricsBucket(bucket.label(), bucket.status(), added,
                last == null ? null : last.cumulativeStoreCount(),
                last == null ? null : last.activeCampaignCount());
    }
}
