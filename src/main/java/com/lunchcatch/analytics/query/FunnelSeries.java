package com.lunchcatch.analytics.query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record FunnelSeries(
        AnalyticsPeriod period,
        LocalDate from,
        LocalDate to,
        LocalDateTime aggregatedAt,
        List<FunnelBucket> items
) {
}
