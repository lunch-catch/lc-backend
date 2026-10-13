package com.launchcatch.adserving.feed.dto;

import com.launchcatch.adserving.engine.Candidate;
import com.launchcatch.adserving.feed.ServedFeed.ServedCard;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.global.config.ClockConfig;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public record FeedCardResponse(
        UUID serveId,
        OffsetDateTime servedAt,
        Long campaignId,
        Long storeId,
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

    // 선착순 오픈 시각은 지금 모든 캠페인이 같다
    private static final String ISSUE_OPEN_TIME = "11:00";
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    public static FeedCardResponse of(ServedCard served, LocalDateTime servedAt) {
        Candidate candidate = served.card().candidate();
        return new FeedCardResponse(
                served.serveId(),
                servedAt.atZone(ClockConfig.ZONE).toOffsetDateTime(),
                candidate.campaignId(),
                candidate.storeId(),
                candidate.storeName(),
                candidate.categoryCode(),
                served.card().distanceM(),
                new Discount(candidate.discountTargetType(), candidate.discountType(), candidate.discountValue()),
                ISSUE_OPEN_TIME,
                format(candidate.usableStartTime()),
                format(candidate.usableEndTime()),
                candidate.issueQuantity(),
                "/v1/campaigns/" + candidate.campaignId() + "/poster");
    }

    private static String format(LocalTime time) {
        return time.format(HOUR_MINUTE);
    }

    public record Discount(DiscountTargetType target, DiscountType type, int value) {
    }
}
