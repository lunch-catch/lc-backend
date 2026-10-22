package com.launchcatch.analytics.query.controller.dto;

import com.launchcatch.analytics.query.CampaignWarning;
import com.launchcatch.analytics.query.CampaignWarningResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record CampaignWarningResponse(
        @Schema(description = "경고가 걸린 캠페인입니다. 결과가 없으면 빈 배열입니다.")
        List<Item> items,
        @Schema(description = "0부터 시작하는 현재 페이지 번호입니다.", example = "0")
        int page,
        @Schema(description = "요청한 페이지 크기입니다.", example = "20")
        int size,
        @Schema(description = "경고가 걸린 전체 캠페인 수입니다.", example = "3")
        long totalElements
) {

    public record Item(
            @Schema(description = "캠페인 ID입니다.", example = "12")
            long campaignId,
            @Schema(description = "기간의 유효 노출 수 합계입니다.", example = "4000")
            long validCount,
            @Schema(description = "기간의 무효 노출 수 합계입니다.", example = "1100")
            long invalidCount,
            @Schema(description = "무효 노출 ÷ 유효 노출입니다. 유효 노출이 0이면 null이고 1을 넘을 수 있습니다.", example = "0.275")
            Double invalidRatio,
            @Schema(description = "무효 노출이 최소 건수 이상이고 유효 노출이 0이거나 비율이 20%를 넘으면 true입니다.", example = "true")
            boolean invalidRatioWarning,
            @Schema(description = "상위 회원의 점유율이 가장 높았던 하루의 유효 노출 회원 수입니다.", example = "4")
            long distinctMemberCount,
            @Schema(description = "상위 회원의 유효 노출 점유율이 가장 높았던 하루의 값입니다.", example = "0.92")
            double topMembersShare,
            @Schema(description = "기간 중 하루라도 소수 회원에 노출이 몰렸으면 true입니다.", example = "true")
            boolean concentrationWarning
    ) {

        static Item from(CampaignWarning warning) {
            return new Item(warning.campaignId(), warning.validCount(), warning.invalidCount(),
                    warning.invalidRatio(), warning.invalidRatioWarning(), warning.distinctMemberCount(),
                    warning.topMembersShare(), warning.concentrationWarning());
        }
    }

    public static CampaignWarningResponse from(CampaignWarningResult result) {
        return new CampaignWarningResponse(
                result.items().stream().map(Item::from).toList(),
                result.page(),
                result.size(),
                result.totalElements());
    }
}
