package com.launchcatch.coupon.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "점주 본인 캠페인의 기간별 쿠폰 수량이다.")
public record OwnerCouponSummaryResponse(
        @Schema(description = "조회한 캠페인 ID다.", example = "501")
        long campaignId,
        @Schema(description = "조회 시작 영업일이며 해당 날짜를 포함한다.", example = "2026-10-17")
        LocalDate from,
        @Schema(description = "조회 종료 영업일이며 해당 날짜를 포함한다.", example = "2026-10-17")
        LocalDate to,
        @Schema(description = "기간 내 생성된 회차의 발급 수량 합계이며 미래 예정 수량을 추정하지 않는다.", example = "50")
        long totalQuantity,
        @Schema(description = "기간 내 회차에서 누적 발급한 수이며 사용·만료·삭제된 쿠폰도 포함한다.", example = "24")
        long issuedCount,
        @Schema(description = "기간 내 실제 사용이 확정된 건수이며 다른 날짜 회차에서 발급된 쿠폰도 포함한다.", example = "12")
        long usedCount,
        @Schema(description = "기간 내 회차의 미발급 AVAILABLE 재고 수이며 발급 후 미사용 수와 다르다.", example = "26")
        long remainingQuantity
) {
}
