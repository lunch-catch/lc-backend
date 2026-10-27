package com.lunchcatch.analytics.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CampaignFunnelResponse(
        @Schema(description = "조회한 캠페인 ID입니다.", example = "801")
        Long campaignId,
        @Schema(description = "조회 시작일입니다. 한국 시각으로 해당 날짜를 포함합니다.")
        LocalDate from,
        @Schema(description = "조회 종료일입니다. 한국 시각으로 해당 날짜를 포함합니다.")
        LocalDate to,
        @Schema(description = "기록된 유효 노출 건수입니다.", example = "1000")
        long impressionCount,
        @Schema(description = "피드에서 찜으로 스와이프한 건수입니다. 가게 상세 찜은 포함하지 않습니다.", example = "200")
        long wishCount,
        @Schema(description = "피드에서 패스로 스와이프한 건수입니다.", example = "600")
        long passCount,
        @Schema(description = "쿠폰 발급 건수입니다.", example = "100")
        long issuedCount,
        @Schema(description = "쿠폰 사용 건수입니다.", example = "60")
        long redeemCount,
        @Schema(description = "찜 수 / 노출 수입니다. 0.2는 20%이며 분모가 0이면 null입니다.", example = "0.2")
        BigDecimal wishRate,
        @Schema(description = "패스 수 / 노출 수입니다. 분모가 0이면 null입니다.", example = "0.6")
        BigDecimal passRate,
        @Schema(description = "발급 수 / 찜 수입니다. 분모가 0이면 null이며 1을 넘을 수 있습니다.", example = "0.5")
        BigDecimal wishToIssueRate,
        @Schema(description = "사용 수 / 발급 수입니다. 분모가 0이면 null이며 1을 넘을 수 있습니다.", example = "0.6")
        BigDecimal issueToRedeemRate
) {
}
