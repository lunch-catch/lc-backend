package com.launchcatch.analytics.query.controller.dto;

import com.launchcatch.analytics.query.AnalyticsPeriod;
import com.launchcatch.analytics.query.StoreMetricsBucket;
import com.launchcatch.analytics.query.StoreMetricsSeries;
import com.launchcatch.global.config.ClockConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record StoreMetricsResponse(
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
                    example = "2026-09-28")
            String label,
            @Schema(description = "FINAL은 집계 완료, IN_PROGRESS는 집계 중입니다.", example = "FINAL")
            String status,
            @Schema(description = "신규 입점 가게 수 합계입니다. 집계 중이면 null입니다.", example = "6")
            Long newStoreCount,
            @Schema(description = "누적 입점 가게 수입니다. 최종 등록한 가게 기준이며 점주 상태와 무관합니다. 구간 마지막 날 값입니다. 집계 중이면 null입니다.",
                    example = "142")
            Long cumulativeStoreCount,
            @Schema(description = "집행 중 캠페인 수입니다. 그날 말 기준이며 구간 마지막 날 값입니다. 집계 중이면 null입니다.",
                    example = "97")
            Long activeCampaignCount
    ) {

        static Item from(StoreMetricsBucket bucket) {
            return new Item(bucket.label(), bucket.status().name(), bucket.newStoreCount(),
                    bucket.cumulativeStoreCount(), bucket.activeCampaignCount());
        }
    }

    public static StoreMetricsResponse from(StoreMetricsSeries series) {
        OffsetDateTime aggregatedAt = series.aggregatedAt() == null
                ? null : series.aggregatedAt().atZone(ClockConfig.ZONE).toOffsetDateTime();
        return new StoreMetricsResponse(series.period(), series.from(), series.to(), aggregatedAt,
                series.items().stream().map(Item::from).toList());
    }
}
