package com.launchcatch.adserving.feed.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record FeedResponse(
        @Schema(description = "SERVING(카드 있음), OUT_OF_SERVING_HOURS(서빙 시간대 밖), NO_CANDIDATE(후보 없음)")
        FeedStatus status,
        @Schema(description = "이 요청 한 번의 카드 묶음 ID. SERVING 이 아니면 null", nullable = true)
        UUID feedId,
        @Schema(description = "후보를 찾는 데 실제로 쓴 반경(미터). OUT_OF_SERVING_HOURS 면 null", nullable = true)
        Integer radiusM,
        @Schema(description = "다음 피드를 요청해도 거부되지 않는 가장 이른 시각")
        OffsetDateTime nextFeedAvailableAt,
        @Schema(description = "카드 목록. 최대 10장. SERVING 이 아니면 빈 배열")
        List<FeedCardResponse> cards
) {

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
