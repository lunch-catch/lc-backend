package com.lunchcatch.adserving.feed.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lunchcatch.adserving.engine.FeedCandidate;
import com.lunchcatch.adserving.engine.entity.DiscountTarget;
import com.lunchcatch.adserving.engine.entity.DiscountType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record FeedCardResponse(
        @Schema(description = "카드 1장의 식별자. 노출과 스와이프 요청에 그대로 돌려보낸다")
        UUID serveId,
        @Schema(description = "서버가 카드를 내려준 시각")
        OffsetDateTime servedAt,
        Long campaignId,
        Long storeId,
        String storeName,
        String categoryCode,
        @Schema(description = "요청 위치에서 가게까지 거리(미터)")
        int distanceM,
        Discount discount,
        @Schema(description = "선착순 오픈 시각. 지금은 항상 11:00", type = "string", example = "11:00")
        @JsonFormat(pattern = "HH:mm")
        LocalTime issueOpenTime,
        @Schema(type = "string", example = "12:00")
        @JsonFormat(pattern = "HH:mm")
        LocalTime usableStartTime,
        @Schema(type = "string", example = "14:00")
        @JsonFormat(pattern = "HH:mm")
        LocalTime usableEndTime,
        @Schema(description = "선착순 발급 수량(표시용). 잔여 수량은 싣지 않는다")
        int issueQuantity,
        @Schema(description = "사용자 포스터 조회 API 의 경로")
        String posterUrl
) {

    // 선착순 오픈 시각은 캠페인마다 다르지 않다(명세의 issueOpenTime).
    private static final LocalTime ISSUE_OPEN_TIME = LocalTime.of(11, 0);

    public static FeedCardResponse of(UUID serveId, OffsetDateTime servedAt, FeedCandidate candidate, int distanceM) {
        return new FeedCardResponse(serveId, servedAt, candidate.campaignId(), candidate.storeId(),
                candidate.storeName(), candidate.categoryCode(), distanceM,
                new Discount(candidate.discountTarget(), candidate.discountType(), candidate.discountValue()),
                ISSUE_OPEN_TIME, candidate.usableStartTime(), candidate.usableEndTime(),
                candidate.issueQuantity(), "/v1/campaigns/" + candidate.campaignId() + "/poster");
    }

    public record Discount(
            @Schema(description = "ALL(전체 메뉴), MENU(특정 메뉴)")
            DiscountTarget target,
            @Schema(description = "PERCENT(할인율), AMOUNT(할인 금액)")
            DiscountType type,
            @Schema(description = "PERCENT 면 1 ~ 100(%), AMOUNT 면 원")
            int value
    ) {
    }
}
