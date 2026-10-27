package com.lunchcatch.coupon.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "매장에서 사용이 확정된 쿠폰의 이력이다.",
        requiredProperties = {"usageHistoryId", "memberCouponId", "discountAmount", "useTime"})
public record CouponUsageHistoryResponse(
        @Schema(description = "쿠폰 사용 이력 ID다.", example = "5001")
        Long usageHistoryId,
        @Schema(description = "사용된 회원 쿠폰 ID다.", example = "1001")
        Long memberCouponId,
        @Schema(description = "사용 시점에 확정해 저장한 할인액(원)이다.", example = "3000")
        int discountAmount,
        @Schema(description = "쿠폰 사용을 확정한 한국 시각이다.", example = "2026-09-30T12:30:00+09:00")
        OffsetDateTime useTime
) {
}
