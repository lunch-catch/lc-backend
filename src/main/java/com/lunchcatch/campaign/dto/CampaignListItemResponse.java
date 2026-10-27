package com.lunchcatch.campaign.dto;

import com.lunchcatch.campaign.contract.CampaignStatus;
import com.lunchcatch.campaign.contract.DiscountTargetType;
import com.lunchcatch.campaign.contract.DiscountType;
import com.lunchcatch.campaign.entity.Campaign;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record CampaignListItemResponse(
        @Schema(description = "캠페인 ID입니다.", example = "501")
        Long campaignId,

        @Schema(description = "그 캠페인이 속한 가게 ID입니다.", example = "20")
        Long storeId,

        @Schema(description = "캠페인 상태입니다.", example = "ACTIVE")
        CampaignStatus status,

        @Schema(description = "할인 대상입니다.", example = "MENU")
        DiscountTargetType discountTargetType,

        @Schema(description = "할인 방식입니다.", example = "PERCENT")
        DiscountType discountType,

        @Schema(description = "할인값입니다.", example = "15")
        int discountValue,

        @Schema(description = "하루 예산(포인트)입니다. 아직 한 번도 설정하지 않았으면 null입니다.")
        Long dailyBudget,

        @Schema(description = "집행 시작일입니다. 아직 한 번도 설정하지 않았으면 null입니다.")
        LocalDate startDate,

        @Schema(description = "집행 종료일입니다. 아직 한 번도 설정하지 않았으면 null입니다.")
        LocalDate endDate,

        @Schema(description = "캠페인을 만든 시각입니다.")
        LocalDateTime createdAt
) {
    public static CampaignListItemResponse from(Campaign campaign) {
        return new CampaignListItemResponse(
                campaign.getId(), campaign.getStoreId(), campaign.getStatus(),
                campaign.getDiscountTargetType(), campaign.getDiscountType(), campaign.getDiscountValue(),
                campaign.getDailyBudget(), campaign.getStartDate(), campaign.getEndDate(),
                campaign.getCreatedAt());
    }
}
