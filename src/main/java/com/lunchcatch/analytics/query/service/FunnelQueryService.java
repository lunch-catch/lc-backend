package com.lunchcatch.analytics.query.service;

import com.lunchcatch.analytics.query.AggregationStatus;
import com.lunchcatch.analytics.query.AnalyticsPeriod;
import com.lunchcatch.analytics.query.FunnelBucket;
import com.lunchcatch.analytics.query.FunnelSeries;
import com.lunchcatch.analytics.query.TimeSeriesBucketState;
import com.lunchcatch.analytics.query.TimeSeriesState;
import com.lunchcatch.analytics.query.repository.FunnelDailyRow;
import com.lunchcatch.analytics.query.repository.FunnelQueryRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/*
 * A-03 퍼널이다. 일별 퍼널 지표를 일, 주, 월로 묶는다.
 *
 * 노출, 찜, 발급, 사용은 흐름이라 모두 합계다. 유효 노출 비율은 일별 비율을 평균하지 않고 합산한 유효와
 * 무효로 valid ÷ (valid + invalid) 를 다시 계산한다. 노출이 하나도 없으면 null 이다. 일부만 걸친 주와
 * 월은 상태 서비스가 요청 범위로 잘라 준 시작과 끝을 쓴다.
 *
 * 집계가 끝나지 않은 구간(IN_PROGRESS)은 수치를 null 로 내린다.
 */
@Service
public class FunnelQueryService {

    private final TimeSeriesStatusService statusService;
    private final FunnelQueryRepository repository;

    public FunnelQueryService(TimeSeriesStatusService statusService, FunnelQueryRepository repository) {
        this.statusService = statusService;
        this.repository = repository;
    }

    public FunnelSeries query(AnalyticsPeriod period, LocalDate from, LocalDate to) {
        TimeSeriesState state = statusService.resolve(period, from, to);
        List<FunnelDailyRow> rows = repository.findBetween(from, to);
        List<FunnelBucket> items = state.items().stream()
                .map(bucket -> toBucket(bucket, rows))
                .toList();
        return new FunnelSeries(period, from, to, state.aggregatedAt(), items);
    }

    private FunnelBucket toBucket(TimeSeriesBucketState bucket, List<FunnelDailyRow> rows) {
        if (bucket.status() != AggregationStatus.FINAL) {
            return new FunnelBucket(bucket.label(), bucket.status(), null, null, null, null, null, null);
        }
        long valid = 0;
        long invalid = 0;
        long wish = 0;
        long issue = 0;
        long redeem = 0;
        for (FunnelDailyRow row : rows) {
            if (row.businessDate().isBefore(bucket.start()) || row.businessDate().isAfter(bucket.end())) {
                continue;
            }
            valid += row.validImpressionCount();
            invalid += row.invalidImpressionCount();
            wish += row.wishCount();
            issue += row.issueCount();
            redeem += row.redeemCount();
        }
        Double ratio = valid + invalid == 0 ? null : (double) valid / (valid + invalid);
        return new FunnelBucket(bucket.label(), bucket.status(), valid, invalid, ratio, wish, issue, redeem);
    }
}
