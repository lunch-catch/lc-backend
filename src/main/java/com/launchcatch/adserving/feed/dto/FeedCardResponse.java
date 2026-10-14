package com.launchcatch.adserving.feed.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

// 슬롯 유형과 배정 근거는 싣지 않는다. 배정 방식이 드러나면 공정성 정책을 거꾸로 이용할 수 있다.
@Schema(description = "피드 카드 한 장이다.")
public record FeedCardResponse(
        @Schema(description = "카드 식별자다. 노출과 스와이프 요청에 그대로 돌려보낸다.")
        UUID serveId,
        @Schema(description = "서버가 카드를 내려준 시각이다.", example = "2026-10-01T10:15:30.123+09:00")
        OffsetDateTime servedAt,
        Long campaignId,
        Long storeId,
        String storeName,
        String categoryCode,
        @Schema(description = "요청 위치에서 가게까지 거리(미터)다.")
        int distanceM,
        Discount discount,
        @Schema(description = "선착순 오픈 시각이다.", example = "11:00")
        String issueOpenTime,
        @Schema(description = "쿠폰 사용 시작 시각이다.", example = "12:00")
        String usableStartTime,
        @Schema(description = "쿠폰 사용 종료 시각이다.", example = "14:00")
        String usableEndTime,
        @Schema(description = "선착순 발급 수량(표시용)이다.")
        int issueQuantity,
        @Schema(description = "사용자 포스터 조회 API 의 경로다.", example = "/v1/campaigns/101/poster")
        String posterUrl
) {

    @Schema(description = "할인 내용이다.")
    public record Discount(
            @Schema(description = "ALL 또는 MENU 다.")
            String target,
            @Schema(description = "PERCENT 또는 AMOUNT 다.")
            String type,
            @Schema(description = "PERCENT 면 1 ~ 100(%), AMOUNT 면 원이다.")
            int value
    ) {
    }
}
