package com.launchcatch.analytics.query.controller.dto;

import com.launchcatch.analytics.query.AnalyticsPeriod;
import com.launchcatch.analytics.query.MemberMetricsBucket;
import com.launchcatch.analytics.query.MemberMetricsSeries;
import com.launchcatch.global.config.ClockConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record MemberMetricsResponse(
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
            @Schema(description = "신규 가입자 합계입니다. 집계 중이면 null입니다.", example = "120")
            Long newMemberCount,
            @Schema(description = "누적 회원 수입니다. 구간 마지막 날 값입니다. 집계 중이면 null입니다.", example = "5430")
            Long cumulativeMemberCount,
            @Schema(description = "일평균 활성 회원 수입니다. 그날 이벤트가 발생한 회원 기준이며 반올림한 정수입니다. 집계 중이면 null입니다.",
                    example = "910")
            Long avgDailyActiveMembers,
            @Schema(description = "누적 탈퇴자 수입니다. 구간 마지막 날 값입니다. 집계 중이면 null입니다.", example = "48")
            Long withdrawnCount,
            @Schema(description = "정지 회원 수입니다. 정지 기능이 구현 제외라 항상 0입니다. 집계 중이면 null입니다.", example = "0")
            Long suspendedCount
    ) {

        static Item from(MemberMetricsBucket bucket) {
            return new Item(bucket.label(), bucket.status().name(), bucket.newMemberCount(),
                    bucket.cumulativeMemberCount(), bucket.avgDailyActiveMembers(), bucket.withdrawnCount(),
                    bucket.suspendedCount());
        }
    }

    public static MemberMetricsResponse from(MemberMetricsSeries series) {
        OffsetDateTime aggregatedAt = series.aggregatedAt() == null
                ? null : series.aggregatedAt().atZone(ClockConfig.ZONE).toOffsetDateTime();
        return new MemberMetricsResponse(series.period(), series.from(), series.to(), aggregatedAt,
                series.items().stream().map(Item::from).toList());
    }
}
