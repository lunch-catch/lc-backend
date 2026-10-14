package com.launchcatch.adserving.feed.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "스와이프 피드 한 묶음이다.",
        requiredProperties = {"status", "feedId", "radiusM", "nextFeedAvailableAt", "cards"})
public record FeedResponse(
        @Schema(description = "SERVING(카드 있음), OUT_OF_SERVING_HOURS(서빙 시간대 밖), NO_CANDIDATE(후보 없음)이다.",
                example = "SERVING")
        FeedStatus status,
        @Schema(description = "이 요청 한 번의 카드 묶음 ID다. SERVING이 아니면 null이다.", nullable = true,
                example = "01925f3a-7c1e-7b2a-9d4e-3f5a6b7c8d9e")
        UUID feedId,
        @Schema(description = "후보를 찾는 데 실제로 쓴 반경(미터)이다. OUT_OF_SERVING_HOURS면 null이다.", nullable = true,
                allowableValues = {"1000", "2000", "3000", "5000"}, example = "1000")
        Integer radiusM,
        @Schema(description = "다음 피드를 요청해도 거부되지 않는 가장 이른 시각이다.", example = "2026-10-01T10:15:30+09:00")
        OffsetDateTime nextFeedAvailableAt,
        @Schema(description = "카드 목록이다. 최대 10장이고 SERVING이 아니면 빈 배열이다.")
        List<FeedCardResponse> cards
) {

    public static FeedResponse outOfServingHours(OffsetDateTime nextFeedAvailableAt) {
        return new FeedResponse(FeedStatus.OUT_OF_SERVING_HOURS, null, null, nextFeedAvailableAt, List.of());
    }

    public static FeedResponse noCandidate(int radiusM, OffsetDateTime nextFeedAvailableAt) {
        return new FeedResponse(FeedStatus.NO_CANDIDATE, null, radiusM, nextFeedAvailableAt, List.of());
    }

    public static FeedResponse serving(UUID feedId, int radiusM, OffsetDateTime nextFeedAvailableAt,
                                       List<FeedCardResponse> cards) {
        return new FeedResponse(FeedStatus.SERVING, feedId, radiusM, nextFeedAvailableAt, cards);
    }
}
