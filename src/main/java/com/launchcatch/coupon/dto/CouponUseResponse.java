package com.launchcatch.coupon.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "쿠폰 사용과 이력을 함께 확정한 결과다.",
        requiredProperties = {"memberCouponId", "usageHistoryId", "discountAmount", "useTime"})
public record CouponUseResponse(
        @Schema(description = "사용 완료된 회원 쿠폰 ID다.", example = "1001") Long memberCouponId,
        @Schema(description = "생성한 사용 이력 ID다.", example = "5001") Long usageHistoryId,
        @Schema(description = "쿠폰 스냅샷으로 확정한 메뉴 한 개의 할인액이다.", example = "3000") int discountAmount,
        @Schema(description = "사용 이력의 createdAt을 한국 시각으로 반환한다.", example = "2026-10-16T12:00:00+09:00")
        OffsetDateTime useTime
) {
}
