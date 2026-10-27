package com.lunchcatch.analytics.query.service;

import com.lunchcatch.analytics.query.AggregationStatus;
import com.lunchcatch.analytics.query.AnalyticsPeriod;
import com.lunchcatch.analytics.query.MemberMetricsBucket;
import com.lunchcatch.analytics.query.MemberMetricsSeries;
import com.lunchcatch.analytics.query.TimeSeriesBucketState;
import com.lunchcatch.analytics.query.TimeSeriesState;
import com.lunchcatch.analytics.query.repository.MemberMetricsDailyRow;
import com.lunchcatch.analytics.query.repository.MemberMetricsQueryRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;

/*
 * A-01 회원 지표다. 일별 회원 지표를 일, 주, 월로 묶는다.
 *
 * 신규 가입자는 흐름이라 합계, 누적과 탈퇴와 정지는 스냅샷이라 묶음 마지막 날의 값이다. 활성 회원은
 * 묶음 안 활성 회원 합을 묶음의 일수로 나눠 반올림한 일평균이다. 일부만 걸친 주와 월은 상태 서비스가
 * 요청 범위로 잘라 준 시작과 끝을 쓰므로 일수도 잘린 범위의 일수다. FINAL 인데 일별 행이 빠진 날은 활성
 * 0 으로 센다.
 *
 * 집계가 끝나지 않은 구간(IN_PROGRESS)은 수치를 null 로 내린다.
 */
@Service
public class MemberMetricsQueryService {

    private final TimeSeriesStatusService statusService;
    private final MemberMetricsQueryRepository repository;

    public MemberMetricsQueryService(TimeSeriesStatusService statusService, MemberMetricsQueryRepository repository) {
        this.statusService = statusService;
        this.repository = repository;
    }

    public MemberMetricsSeries query(AnalyticsPeriod period, LocalDate from, LocalDate to) {
        TimeSeriesState state = statusService.resolve(period, from, to);
        List<MemberMetricsDailyRow> rows = repository.findBetween(from, to);
        List<MemberMetricsBucket> items = state.items().stream()
                .map(bucket -> toBucket(bucket, rows))
                .toList();
        return new MemberMetricsSeries(period, from, to, state.aggregatedAt(), items);
    }

    private MemberMetricsBucket toBucket(TimeSeriesBucketState bucket, List<MemberMetricsDailyRow> rows) {
        if (bucket.status() != AggregationStatus.FINAL) {
            return new MemberMetricsBucket(bucket.label(), bucket.status(), null, null, null, null, null);
        }
        long added = 0;
        long active = 0;
        MemberMetricsDailyRow last = null;
        for (MemberMetricsDailyRow row : rows) {
            if (row.businessDate().isBefore(bucket.start()) || row.businessDate().isAfter(bucket.end())) {
                continue;
            }
            added += row.newMemberCount();
            active += row.activeMemberCount();
            if (row.businessDate().equals(bucket.end())) {
                last = row;
            }
        }
        long days = ChronoUnit.DAYS.between(bucket.start(), bucket.end()) + 1;
        long average = BigDecimal.valueOf(active)
                .divide(BigDecimal.valueOf(days), 0, RoundingMode.HALF_UP)
                .longValue();
        return new MemberMetricsBucket(bucket.label(), bucket.status(), added,
                last == null ? null : last.cumulativeMemberCount(), average,
                last == null ? null : last.withdrawnCount(), last == null ? null : last.suspendedCount());
    }
}
