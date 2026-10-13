package com.launchcatch.adserving.feed.dto;

import com.launchcatch.adserving.engine.FeedCandidate;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/*
 * 피드 카드 한 장이다.
 * 잔여 수량은 쿠폰이 소유한 값이라 싣지 않는다(feed-api-design.md)
 */
public record FeedCardResponse(
        UUID serveId,
        OffsetDateTime servedAt,
        long campaignId,
        long storeId,
        String storeName,
        String categoryCode,
        int distanceM,
        Discount discount,
        String issueOpenTime,
        String usableStartTime,
        String usableEndTime,
        int issueQuantity,
        String posterUrl
) {

    /*
     * 선착순 오픈 시각이다.
     * 지금은 모든 캠페인이 같다(feed-api-spec.md)
     */
    private static final String ISSUE_OPEN_TIME = "11:00";

    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    public static FeedCardResponse of(UUID serveId, OffsetDateTime servedAt, FeedCandidate candidate, int distanceM) {
        return new FeedCardResponse(serveId, servedAt, candidate.campaignId(), candidate.storeId(),
                candidate.storeName(), candidate.categoryCode(), distanceM,
                new Discount(candidate.discountTarget(), candidate.discountType(), candidate.discountValue()),
                ISSUE_OPEN_TIME, format(candidate.usableStartTime()), format(candidate.usableEndTime()),
                candidate.issueQuantity(), posterUrl(candidate.campaignId()));
    }

    /*
     * 사용자 포스터 조회 API 의 경로다(poster-api-spec.md).
     * 활성화 뒤에는 포스터를 고칠 수 없어 같은 주소는 항상 같은 포스터라 버전을 붙이지 않는다(feed-api-design.md).
     */
    private static String posterUrl(long campaignId) {
        return "/v1/campaigns/" + campaignId + "/poster";
    }

    private static String format(LocalTime time) {
        return time.format(HOUR_MINUTE);
    }

    public record Discount(DiscountTargetType target, DiscountType type, int value) {
    }
}
