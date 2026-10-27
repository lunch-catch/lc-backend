package com.lunchcatch.analytics.event.controller.dto;

import java.util.List;

public record EventBatchRequest(List<EventRequest> events) {
}
