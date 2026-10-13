package com.launchcatch.adserving.feed.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/*
 * GET /v1/feed 의 응답이다. 필드의 뜻과 null 이 오는 때는 feed-api-spec.md 의 필드 표가 갖는다.
 * 슬롯 유형과 배정 근거는 싣지 않는다. 배정 방식이 드러나면 공정성 정책을 거꾸로 이용할 수 있다(feed-api-design.md).
 */
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

    public static FeedResponse serving(UUID feedId, int radiusM, OffsetDateTime nextFeedAvailableAt,
                                       List<FeedCardResponse> cards) {
        return new FeedResponse(FeedStatus.SERVING, feedId, radiusM, nextFeedAvailableAt, cards);
    }

    public static FeedResponse outOfServingHours(OffsetDateTime nextFeedAvailableAt) {
        return new FeedResponse(FeedStatus.OUT_OF_SERVING_HOURS, null, null, nextFeedAvailableAt, List.of());
    }

    public static FeedResponse noCandidate(int radiusM, OffsetDateTime nextFeedAvailableAt) {
        return new FeedResponse(FeedStatus.NO_CANDIDATE, null, radiusM, nextFeedAvailableAt, List.of());
    }
}
