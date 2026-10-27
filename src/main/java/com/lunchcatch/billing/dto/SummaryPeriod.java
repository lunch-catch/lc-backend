package com.lunchcatch.billing.dto;

import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.global.exception.CommonErrorCode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/*
 * 관리자 합계의 구간 단위다. 단위마다 상한이 다르다.
 * 일별은 92일, 주별은 53주, 월별은 13개월이고 넘으면 COMMON-002 로 거절한다.
 * 주의 시작은 월요일이다.
 */
public enum SummaryPeriod {

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

    SummaryPeriod(int maxBuckets) {
        this.maxBuckets = maxBuckets;
    }

    abstract LocalDate bucketStart(LocalDate date);

    abstract LocalDate bucketEnd(LocalDate bucketStart);

    abstract String label(LocalDate bucketStart);

    // 기간을 구간으로 나눠 시간순으로 돌려준다. 상한을 넘으면 거절한다
    public List<PeriodBucket> buckets(DateRange range) {
        List<PeriodBucket> buckets = new ArrayList<>();
        LocalDate cursor = bucketStart(range.from());
        while (!cursor.isAfter(range.to())) {
            if (buckets.size() == maxBuckets) {
                throw new BillingException(CommonErrorCode.INVALID_INPUT);
            }
            LocalDate start = cursor.isBefore(range.from()) ? range.from() : cursor;
            LocalDate fullEnd = bucketEnd(cursor);
            LocalDate end = fullEnd.isAfter(range.to()) ? range.to() : fullEnd;
            buckets.add(new PeriodBucket(label(cursor), start, end));
            cursor = fullEnd.plusDays(1);
        }
        return buckets;
    }
}
