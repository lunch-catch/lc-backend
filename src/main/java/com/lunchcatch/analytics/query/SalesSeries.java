package com.lunchcatch.analytics.query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record SalesSeries(
        AnalyticsPeriod period,
        LocalDate from,
        LocalDate to,
        LocalDateTime aggregatedAt,
        List<SalesBucket> items
) {
}
