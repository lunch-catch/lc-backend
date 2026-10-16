package com.launchcatch.analytics.query.service;

import com.launchcatch.analytics.query.AggregationStatus;
import com.launchcatch.analytics.query.AnalyticsDateRange;
import com.launchcatch.analytics.query.AnalyticsPeriod;
import com.launchcatch.analytics.query.TimeSeriesBucket;
import com.launchcatch.analytics.query.TimeSeriesBucketState;
import com.launchcatch.analytics.query.TimeSeriesState;
import com.launchcatch.ops.contract.BatchExecutionQueryService;
import com.launchcatch.ops.contract.BatchExecutionRecord;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TimeSeriesStatusService {

    private static final String JOB_NAME = "DAILY_AGGREGATION";
    private static final String SUCCESS = "SUCCESS";

    private final BatchExecutionQueryService batchExecutions;

    public TimeSeriesState resolve(AnalyticsPeriod period, LocalDate from, LocalDate to) {
        AnalyticsDateRange range = new AnalyticsDateRange(from, to);
        Map<LocalDate, List<BatchExecutionRecord>> recordsByDate = range.dates().stream()
                .collect(Collectors.toMap(Function.identity(), batchExecutions::findByBusinessDate));

        List<TimeSeriesBucketState> items = period.buckets(range).stream()
                .map(bucket -> toState(bucket, recordsByDate))
                .toList();
        LocalDateTime aggregatedAt = recordsByDate.values().stream()
                .flatMap(List::stream)
                .filter(this::isSuccessfulAggregation)
                .map(BatchExecutionRecord::finishedAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return new TimeSeriesState(aggregatedAt, items);
    }

    private TimeSeriesBucketState toState(
            TimeSeriesBucket bucket,
            Map<LocalDate, List<BatchExecutionRecord>> recordsByDate) {
        boolean finalized = recordsByDate.entrySet().stream()
                .filter(entry -> bucket.contains(entry.getKey()))
                .allMatch(entry -> entry.getValue().stream().anyMatch(this::isSuccessfulAggregation));
        AggregationStatus status = finalized ? AggregationStatus.FINAL : AggregationStatus.IN_PROGRESS;
        return new TimeSeriesBucketState(bucket.label(), bucket.start(), bucket.end(), status);
    }

    private boolean isSuccessfulAggregation(BatchExecutionRecord record) {
        return JOB_NAME.equals(record.jobName())
                && SUCCESS.equals(record.status())
                && record.finishedAt() != null;
    }
}
