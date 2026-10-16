package com.launchcatch.analytics.query;

import com.launchcatch.analytics.exception.AnalyticsErrorCode;
import com.launchcatch.analytics.exception.AnalyticsException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

public enum AnalyticsPeriod {

    DAILY(92) {
        @Override
        LocalDate bucketStart(LocalDate date) {
            return date;
        }

        @Override
        LocalDate bucketEnd(LocalDate bucketStart) {
            return bucketStart;
        }

        @Override
        String label(LocalDate bucketStart) {
            return bucketStart.toString();
        }
    },

    WEEKLY(53) {
        @Override
        LocalDate bucketStart(LocalDate date) {
            return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }

        @Override
        LocalDate bucketEnd(LocalDate bucketStart) {
            return bucketStart.plusDays(6);
        }

        @Override
        String label(LocalDate bucketStart) {
            return bucketStart.toString();
        }
    },

    MONTHLY(13) {
        @Override
        LocalDate bucketStart(LocalDate date) {
            return date.withDayOfMonth(1);
        }

        @Override
        LocalDate bucketEnd(LocalDate bucketStart) {
            return YearMonth.from(bucketStart).atEndOfMonth();
        }

        @Override
        String label(LocalDate bucketStart) {
            return YearMonth.from(bucketStart).toString();
        }
    };

    private final int maxBuckets;

    AnalyticsPeriod(int maxBuckets) {
        this.maxBuckets = maxBuckets;
    }

    abstract LocalDate bucketStart(LocalDate date);

    abstract LocalDate bucketEnd(LocalDate bucketStart);

    abstract String label(LocalDate bucketStart);

    public List<TimeSeriesBucket> buckets(AnalyticsDateRange range) {
        List<TimeSeriesBucket> buckets = new ArrayList<>();
        LocalDate cursor = bucketStart(range.from());
        while (!cursor.isAfter(range.to())) {
            if (buckets.size() == maxBuckets) {
                throw new AnalyticsException(AnalyticsErrorCode.INVALID_PERIOD);
            }
            LocalDate start = cursor.isBefore(range.from()) ? range.from() : cursor;
            LocalDate fullEnd = bucketEnd(cursor);
            LocalDate end = fullEnd.isAfter(range.to()) ? range.to() : fullEnd;
            buckets.add(new TimeSeriesBucket(label(cursor), start, end));
            cursor = fullEnd.plusDays(1);
        }
        return List.copyOf(buckets);
    }
}
