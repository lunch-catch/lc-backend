package com.launchcatch.analytics.query;

import java.time.LocalDate;

public record TimeSeriesBucket(String label, LocalDate start, LocalDate end) {

    public boolean contains(LocalDate date) {
        return !date.isBefore(start) && !date.isAfter(end);
    }
}
