package com.launchcatch.analytics.query;

import com.launchcatch.analytics.exception.AnalyticsErrorCode;
import com.launchcatch.analytics.exception.AnalyticsException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/*
 * 부정 관리 조회(A-09, A-10)의 기간이다. 두 날짜가 모두 있어야 하고 from 이 to 보다 늦으면 안 되며 31일을
 * 넘을 수 없다. 어기면 ANALYTICS-001 이다.
 */
public record FraudDateRange(LocalDate from, LocalDate to) {

    public static final int MAX_DAYS = 31;

    public FraudDateRange {
        if (from == null || to == null || to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new AnalyticsException(AnalyticsErrorCode.INVALID_PERIOD);
        }
    }
}
