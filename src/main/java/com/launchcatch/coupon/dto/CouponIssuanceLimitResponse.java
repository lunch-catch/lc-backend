package com.launchcatch.coupon.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "당일 발급 한도 정보다.", requiredProperties = {"date", "issuedCount", "dailyLimit", "dailyLimitReached"})
public record CouponIssuanceLimitResponse(
        @Schema(description = "한국 날짜 기준 영업일이다.", example = "2026-10-13")
        LocalDate date,
        @Schema(description = "삭제한 쿠폰을 제외한 당일 발급 수다.", example = "3", minimum = "0")
        int issuedCount,
        @Schema(description = "일일 발급 한도이며 기본 3장이다.", example = "3")
        int dailyLimit,
        @Schema(description = "발급 수가 한도 이상이면 true다.", example = "true")
        boolean dailyLimitReached
) {
}
