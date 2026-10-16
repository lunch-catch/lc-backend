package com.launchcatch.analytics.query;

import com.launchcatch.analytics.exception.AnalyticsErrorCode;
import com.launchcatch.analytics.exception.AnalyticsException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public record AnalyticsDateRange(LocalDate from, LocalDate to) {

    public AnalyticsDateRange {
        if (from == null || to == null || to.isBefore(from)) {
            throw new AnalyticsException(AnalyticsErrorCode.INVALID_PERIOD);
        }
    }

    public List<LocalDate> dates() {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            dates.add(date);
        }
        return List.copyOf(dates);
    }
}
