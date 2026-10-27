package com.lunchcatch.billing.dto;

import java.util.List;

public record SpendSummaryResponse(List<SpendSummaryItem> items) {
}
