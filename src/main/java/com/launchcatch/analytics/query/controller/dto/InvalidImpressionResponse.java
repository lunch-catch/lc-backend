package com.launchcatch.analytics.query.controller.dto;

import com.launchcatch.analytics.query.InvalidImpressionResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record InvalidImpressionResponse(
        @Schema(description = "조회 시작일(KST)입니다.", example = "2026-09-24")
        LocalDate from,
        @Schema(description = "조회 종료일(KST)입니다.", example = "2026-09-30")
        LocalDate to,
        @Schema(description = "조회 조건 전체의 사유별 합계입니다. 조건이 허용하는 사유는 건수가 0이어도 들어 있습니다.")
        List<ReasonSummary> summaryByReason,
        @Schema(description = "회원, 캠페인, 사유 단위 항목입니다. 결과가 없으면 빈 배열입니다.")
        List<Item> items,
        @Schema(description = "0부터 시작하는 현재 페이지 번호입니다.", example = "0")
        int page,
        @Schema(description = "요청한 페이지 크기입니다.", example = "20")
        int size,
        @Schema(description = "조회 조건에 맞는 전체 항목 수입니다.", example = "1")
        long totalElements
) {

    public record ReasonSummary(
            @Schema(description = "무효 사유입니다. EXPIRED_OR_UNKNOWN, NOT_OWNER 중 하나입니다.",
                    example = "EXPIRED_OR_UNKNOWN")
            String reasonCode,
            @Schema(description = "기간 안의 무효 노출 수 합계입니다.", example = "1210")
            long count
    ) {
    }

    public record Item(
            @Schema(description = "노출을 보낸 회원 ID입니다.", example = "55")
            long memberId,
            @Schema(description = "요청이 가리킨 캠페인 ID입니다. 서버가 발급하지 않은 serve_id의 노출은 없는 캠페인일 수 있습니다.",
                    example = "12")
            long campaignId,
            @Schema(description = "무효 사유입니다.", example = "NOT_OWNER")
            String reasonCode,
            @Schema(description = "기간 안의 무효 노출 수입니다.", example = "31")
            long count
    ) {
    }

    public static InvalidImpressionResponse from(InvalidImpressionResult result) {
        return new InvalidImpressionResponse(
                result.from(),
                result.to(),
                result.summaryByReason().stream()
                        .map(total -> new ReasonSummary(total.reasonCode().name(), total.count()))
                        .toList(),
                result.items().stream()
                        .map(row -> new Item(row.memberId(), row.campaignId(), row.reasonCode().name(), row.count()))
                        .toList(),
                result.page(),
                result.size(),
                result.totalElements());
    }
}
