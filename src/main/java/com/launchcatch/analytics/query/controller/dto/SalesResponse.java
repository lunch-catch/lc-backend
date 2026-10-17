package com.launchcatch.analytics.query.controller.dto;

import com.launchcatch.analytics.query.AnalyticsPeriod;
import com.launchcatch.analytics.query.SalesBucket;
import com.launchcatch.analytics.query.SalesSeries;
import com.launchcatch.global.config.ClockConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record SalesResponse(
        @Schema(description = "묶음 단위입니다. DAILY, WEEKLY, MONTHLY 중 하나입니다.", example = "MONTHLY")
        AnalyticsPeriod period,
        @Schema(description = "조회 시작일(KST)입니다.", example = "2026-07-01")
        LocalDate from,
        @Schema(description = "조회 종료일(KST)입니다.", example = "2026-09-30")
        LocalDate to,
        @Schema(description = "요청 범위에서 마지막으로 성공한 집계 실행 시각입니다. 성공한 집계가 없으면 null입니다.")
        OffsetDateTime aggregatedAt,
        List<Item> items
) {

    public record Item(
            @Schema(description = "구간 이름입니다. 일별은 날짜, 주별은 월요일 날짜, 월별은 yyyy-MM입니다.",
                    example = "2026-09")
            String label,
            @Schema(description = "FINAL은 집계 완료, IN_PROGRESS는 집계 중입니다.", example = "FINAL")
            String status,
            @Schema(description = "충전액 합계입니다. 집계 중이면 null입니다.", example = "5200000")
            Long chargeAmount,
            @Schema(description = "소진액(광고 매출)입니다. 차감 합에서 과오 차감 정정을 뺀 값이며 음수일 수 있습니다. 집계 중이면 null입니다.",
                    example = "4100000")
            Long spentAmount,
            @Schema(description = "환불액 합계입니다. 집계 중이면 null입니다.", example = "300000")
            Long refundAmount,
            @Schema(description = "조정액(±) 합계입니다. 집계 중이면 null입니다.", example = "-1000")
            Long adjustAmount,
            @Schema(description = "미소진 잔액입니다. 구간 마지막 날 값입니다. 집계 중이면 null입니다.", example = "2750000")
            Long unspentBalance
    ) {

        static Item from(SalesBucket bucket) {
            return new Item(bucket.label(), bucket.status().name(), bucket.chargeAmount(), bucket.spentAmount(),
                    bucket.refundAmount(), bucket.adjustAmount(), bucket.unspentBalance());
        }
    }

    public static SalesResponse from(SalesSeries series) {
        OffsetDateTime aggregatedAt = series.aggregatedAt() == null
                ? null : series.aggregatedAt().atZone(ClockConfig.ZONE).toOffsetDateTime();
        return new SalesResponse(series.period(), series.from(), series.to(), aggregatedAt,
                series.items().stream().map(Item::from).toList());
    }
}
