package com.lunchcatch.coupon.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "점주가 관리하는 매장의 날짜별 쿠폰 사용 건수다.")
public record OwnerCouponDailyUsageResponse(
        @Schema(description = "조회한 매장 ID다.", example = "20")
        long storeId,
        @Schema(description = "한국 날짜 기준 조회 시작일이며 해당 날짜를 포함한다.", example = "2026-10-14")
        LocalDate from,
        @Schema(description = "한국 날짜 기준 조회 종료일이며 해당 날짜를 포함한다.", example = "2026-10-17")
        LocalDate to,
        @Schema(description = "조회 기간에 실제 사용이 확정된 쿠폰 건수의 합계다.", example = "46")
        long totalUsedCount,
        @Schema(description = "날짜 오름차순이며 사용이 없는 날도 0건으로 포함한다.")
        List<Item> items
) {
    public OwnerCouponDailyUsageResponse {
        items = List.copyOf(items);
    }

    public record Item(
            @Schema(description = "실제 사용 시각의 한국 날짜다.", example = "2026-10-14")
            LocalDate businessDate,
            @Schema(description = "해당 날짜에 매장에서 사용한 쿠폰 건수이며 고유 회원 수와 다르다.", example = "9")
            long usedCount
    ) {
    }
}
