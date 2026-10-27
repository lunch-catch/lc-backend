package com.lunchcatch.analytics.query.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record OwnerReportResponse(
        @Schema(description = "조회 시작일입니다. 조회 범위에 포함합니다.", example = "2026-10-16")
        LocalDate from,
        @Schema(description = "조회 종료일입니다. 조회 범위에 포함합니다.", example = "2026-10-17")
        LocalDate to,
        @Schema(description = "조회 기간의 최종 소진 포인트 합계입니다.", example = "8400")
        long totalSpentAmount,
        @Schema(description = "날짜 오름차순의 캠페인 집계입니다. 집계가 없으면 빈 목록입니다.")
        List<Item> items
) {

    public record Item(
            @Schema(description = "집계 영업일입니다.", example = "2026-10-16")
            LocalDate businessDate,
            @Schema(description = "해당 날짜에 집행한 캠페인 ID입니다.", example = "801")
            long campaignId,
            @Schema(description = "해당 영업일에 실제 예약·배정된 포인트입니다.", example = "7000")
            long dailyBudget,
            @Schema(description = "해당 영업일에 적용된 과금 노출 1회당 단가입니다.", example = "2")
            long impressionUnitCost,
            @Schema(description = "미과금 노출을 포함한 유효 노출 수입니다.", example = "2400")
            long validImpressionCount,
            @Schema(description = "무효 노출 수입니다.", example = "8")
            long invalidImpressionCount,
            @Schema(description = "해당 영업일에 발생한 찜 건수입니다.", example = "180")
            long wishCount,
            @Schema(description = "해당 영업일 쿠폰 회차의 발급 건수입니다.", example = "190")
            long issuedCount,
            @Schema(description = "해당 영업일에 사용된 쿠폰 건수입니다. 다른 날 발급된 쿠폰도 포함합니다.", example = "76")
            long redeemCount,
            @Schema(description = "과금으로 차감된 포인트입니다.", example = "4000")
            long chargedAmount,
            @Schema(description = "과오 차감 정정으로 돌려준 포인트입니다. 일반 잔액 환불액은 제외합니다.", example = "100")
            long refundedAmount,
            @Schema(description = "최종 소진 포인트입니다. chargedAmount에서 refundedAmount를 뺀 값입니다.", example = "3900")
            long spentAmount
    ) {
    }
}
