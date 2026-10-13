package com.launchcatch.billing.dto;

import java.time.LocalDate;
import java.util.List;

public record AdminPointSummaryResponse(
        Long ownerId,
        SummaryPeriod period,
        LocalDate from,
        LocalDate to,
        List<AdminPointSummaryItem> items
) {
}
