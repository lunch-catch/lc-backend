package com.lunchcatch.analytics.query.controller.dto;

import com.lunchcatch.analytics.query.AnalyticsPeriod;
import com.lunchcatch.analytics.query.FunnelBucket;
import com.lunchcatch.analytics.query.FunnelSeries;
import com.lunchcatch.global.config.ClockConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record FunnelResponse(
        @Schema(description = "묶음 단위입니다. DAILY, WEEKLY, MONTHLY 중 하나입니다.", example = "DAILY")
        AnalyticsPeriod period,
        @Schema(description = "조회 시작일(KST)입니다.", example = "2026-09-01")
        LocalDate from,
        @Schema(description = "조회 종료일(KST)입니다.", example = "2026-09-30")
        LocalDate to,
        @Schema(description = "요청 범위에서 마지막으로 성공한 집계 실행 시각입니다. 성공한 집계가 없으면 null입니다.")
        OffsetDateTime aggregatedAt,
        List<Item> items
) {

    public record Item(
            @Schema(description = "구간 이름입니다. 일별은 날짜, 주별은 월요일 날짜, 월별은 yyyy-MM입니다.",
                    example = "2026-09-30")
            String label,
            @Schema(description = "FINAL은 집계 완료, IN_PROGRESS는 집계 중입니다.", example = "FINAL")
            String status,
            @Schema(description = "유효 노출 수 합계입니다. 쿠폰 소진 뒤 노출과 미과금 노출을 포함합니다. 집계 중이면 null입니다.",
                    example = "182000")
            Long validImpressionCount,
            @Schema(description = "무효 노출 수 합계입니다. EXPIRED_OR_UNKNOWN과 NOT_OWNER의 합입니다. 집계 중이면 null입니다.",
                    example = "1450")
            Long invalidImpressionCount,
            @Schema(description = "유효 노출 비율입니다. 합산한 유효 ÷ (유효 + 무효)로 다시 계산합니다. 노출이 없거나 집계 중이면 null입니다.",
                    example = "0.992")
            Double validRatio,
            @Schema(description = "피드 카드에서 찜한 수 합계입니다. 가게 상세에서 한 찜은 들어가지 않습니다. 집계 중이면 null입니다.",
                    example = "9100")
            Long wishCount,
            @Schema(description = "쿠폰 발급 수 합계입니다. 집계 중이면 null입니다.", example = "2300")
            Long issueCount,
            @Schema(description = "쿠폰 사용 수 합계입니다. 집계 중이면 null입니다.", example = "700")
            Long redeemCount
    ) {

        static Item from(FunnelBucket bucket) {
            return new Item(bucket.label(), bucket.status().name(), bucket.validImpressionCount(),
                    bucket.invalidImpressionCount(), bucket.validRatio(), bucket.wishCount(),
                    bucket.issueCount(), bucket.redeemCount());
        }
    }

    public static FunnelResponse from(FunnelSeries series) {
        OffsetDateTime aggregatedAt = series.aggregatedAt() == null
                ? null : series.aggregatedAt().atZone(ClockConfig.ZONE).toOffsetDateTime();
        return new FunnelResponse(series.period(), series.from(), series.to(), aggregatedAt,
                series.items().stream().map(Item::from).toList());
    }
}
