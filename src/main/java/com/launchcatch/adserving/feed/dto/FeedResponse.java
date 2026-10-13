package com.launchcatch.adserving.feed.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record FeedResponse(
        FeedStatus status,
        UUID feedId,
        Integer radiusM,
        OffsetDateTime nextFeedAvailableAt,
        List<FeedCardResponse> cards
) {

    public FeedResponse {
        cards = List.copyOf(cards);
    }

    public static FeedResponse outOfServingHours(OffsetDateTime nextFeedAvailableAt) {
        return new FeedResponse(FeedStatus.OUT_OF_SERVING_HOURS, null, null, nextFeedAvailableAt, List.of());
    }

    public static FeedResponse noCandidate(int radiusM, OffsetDateTime nextFeedAvailableAt) {
        return new FeedResponse(FeedStatus.NO_CANDIDATE, null, radiusM, nextFeedAvailableAt, List.of());
    }

    public static FeedResponse serving(
            UUID feedId, int radiusM, OffsetDateTime nextFeedAvailableAt, List<FeedCardResponse> cards) {
        return new FeedResponse(FeedStatus.SERVING, feedId, radiusM, nextFeedAvailableAt, cards);
    }
}
