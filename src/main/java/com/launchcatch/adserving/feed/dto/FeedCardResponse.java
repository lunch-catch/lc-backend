package com.launchcatch.adserving.feed.dto;

import com.launchcatch.adserving.engine.entity.AdCandidate;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Schema(description = "피드 카드 1장이다.",
        requiredProperties = {"serveId", "servedAt", "campaignId", "storeId", "storeName", "categoryCode", "distanceM",
                "discount", "issueOpenTime", "usableStartTime", "usableEndTime", "issueQuantity", "posterUrl"})
public record FeedCardResponse(
        @Schema(description = "카드 1장의 식별자다. 노출과 스와이프 요청에 그대로 돌려보낸다.",
                example = "01925f3a-7c1e-7b2a-9d4e-3f5a6b7c8da0")
        UUID serveId,
        @Schema(description = "서버가 카드를 내려준 시각이다.", example = "2026-10-01T10:15:30.123+09:00")
        OffsetDateTime servedAt,
        @Schema(description = "캠페인 ID다.", example = "101")
        Long campaignId,
        @Schema(description = "가게 ID다.", example = "55")
        Long storeId,
        @Schema(description = "상호다.", example = "점심식당")
        String storeName,
        @Schema(description = "업종 코드다.", example = "KOREAN")
        String categoryCode,
        @Schema(description = "요청 위치에서 가게까지 거리(미터)다.", example = "420")
        int distanceM,
        DiscountResponse discount,
        @Schema(description = "선착순 오픈 시각이다. 지금은 항상 11:00이다.", example = "11:00")
        String issueOpenTime,
        @Schema(description = "쿠폰 사용 시작 시각이다.", example = "12:00")
        String usableStartTime,
        @Schema(description = "쿠폰 사용 종료 시각이다.", example = "14:00")
        String usableEndTime,
        @Schema(description = "선착순 발급 수량(표시용)이다.", example = "30")
        int issueQuantity,
        @Schema(description = "포스터 주소다. 사용자 포스터 조회 API의 경로다.", example = "/v1/campaigns/101/poster")
        String posterUrl
) {

    // 선착순 오픈 시각은 모든 캠페인이 같다(명세서 issueOpenTime)
    private static final LocalTime ISSUE_OPEN_TIME = LocalTime.of(11, 0);
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    public static FeedCardResponse of(UUID serveId, OffsetDateTime servedAt, AdCandidate candidate, int distanceM) {
        return new FeedCardResponse(
                serveId,
                servedAt,
                candidate.getCampaignId(),
                candidate.getStoreId(),
                candidate.getStoreName(),
                candidate.getCategoryCode(),
                distanceM,
                new DiscountResponse(candidate.getDiscountTarget(), candidate.getDiscountType(),
                        candidate.getDiscountValue()),
                ISSUE_OPEN_TIME.format(HOUR_MINUTE),
                candidate.getUsableStartTime().format(HOUR_MINUTE),
                candidate.getUsableEndTime().format(HOUR_MINUTE),
                candidate.getIssueQuantity(),
                "/v1/campaigns/" + candidate.getCampaignId() + "/poster");
    }
}
