package com.lunchcatch.coupon.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "조회 시점의 매장별 쿠폰 이벤트와 발급 가능 상태다.", requiredProperties = {"serverTime", "items"})
public record CouponEventStatusResponse(
        @Schema(description = "요청 처리 시작 시 한 번 읽은 한국 시각이며 밀리초 세 자리를 포함한다.",
                example = "2026-10-14T12:00:00.000+09:00")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        OffsetDateTime serverTime,
        @Schema(description = "입력 순서대로 반환하는 매장별 상태다.")
        List<Item> items
) {
    public CouponEventStatusResponse {
        items = List.copyOf(items);
    }

    @Schema(description = "한 매장의 쿠폰 이벤트 상태다.", requiredProperties = {"storeId", "hasCampaign", "eventId",
            "eventState", "remainingQuantity", "usableStartTime", "usableEndTime", "hasIssuedToday", "wished",
            "canIssue", "cannotIssueReason"})
    public record Item(
            @Schema(description = "조회한 매장 ID다.", example = "10")
            Long storeId,
            @Schema(description = "ACTIVE 또는 SCHEDULED 캠페인이 있으면 true다.", example = "true")
            boolean hasCampaign,
            @Schema(description = "오늘 이벤트 ID이며 조회 대상이 없으면 null이다.", example = "501", nullable = true)
            Long eventId,
            @Schema(description = "오늘 이벤트의 발급 상태다.", example = "OPEN")
            EventState eventState,
            @Schema(description = "이벤트의 AVAILABLE 재고 수량이며 이벤트나 재고가 없으면 0이다.", example = "42", minimum = "0")
            int remainingQuantity,
            @Schema(description = "스냅샷에 저장한 사용 시작 시각이며 이벤트가 없으면 null이다.", nullable = true)
            OffsetDateTime usableStartTime,
            @Schema(description = "스냅샷에 저장한 사용 종료 시각이며 이벤트가 없으면 null이다.", nullable = true)
            OffsetDateTime usableEndTime,
            @Schema(description = "본인이 오늘 이벤트에서 이미 발급받았으면 true이며 삭제한 쿠폰도 포함한다.")
            boolean hasIssuedToday,
            @Schema(description = "이벤트의 캠페인에 유효한 찜이 있으면 true다.")
            boolean wished,
            @Schema(description = "조회 시점에 발급 가능하면 true이며 재고를 예약하지 않는다.")
            boolean canIssue,
            @Schema(description = "가장 우선하는 발급 불가 사유이며 발급 가능하면 null이다.", nullable = true)
            CannotIssueReason cannotIssueReason
    ) {
    }

    public enum EventState {
        SCHEDULED, OPEN, SOLD_OUT, CLOSED, NO_EVENT
    }

    public enum CannotIssueReason {
        NO_EVENT, ACCOUNT_NOT_ELIGIBLE, ALREADY_ISSUED, DAILY_LIMIT_REACHED, NOT_WISHED, NOT_OPEN, SOLD_OUT
    }
}
