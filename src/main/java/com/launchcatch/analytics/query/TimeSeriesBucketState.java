package com.launchcatch.analytics.query;

import java.time.LocalDate;

public record TimeSeriesBucketState(
        String label,
        LocalDate start,
        LocalDate end,
        AggregationStatus status
) {
}
