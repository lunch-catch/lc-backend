package com.lunchcatch.analytics.query;

import java.time.LocalDateTime;
import java.util.List;

public record TimeSeriesState(LocalDateTime aggregatedAt, List<TimeSeriesBucketState> items) {

    public TimeSeriesState {
        items = List.copyOf(items);
    }
}
