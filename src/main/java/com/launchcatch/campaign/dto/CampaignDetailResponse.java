package com.launchcatch.campaign.dto;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.PauseReason;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.entity.Campaign;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public record CampaignDetailResponse(
        @Schema(description = "캠페인 ID입니다.", example = "501")
        Long campaignId,

        @Schema(description = "그 캠페인이 속한 가게 ID입니다.", example = "20")
        Long storeId,

        @Schema(description = "캠페인 상태입니다.", example = "ACTIVE")
        CampaignStatus status,

        @Schema(description = "PAUSED일 때 중단 사유입니다. 그 외 상태면 null입니다.")
        PauseReason pausedReason,

        @Schema(description = "할인 대상입니다.", example = "MENU")
        DiscountTargetType discountTargetType,

        @Schema(description = "할인 방식입니다.", example = "PERCENT")
        DiscountType discountType,

        @Schema(description = "할인값입니다.", example = "15")
        int discountValue,

        @Schema(description = "할인 대상 메뉴 ID입니다. discountTargetType이 ALL이면 null입니다.")
        Long targetMenuId,

        @Schema(description = "발급 수량입니다.", example = "100")
        int issueQuantity,

        @Schema(description = "쿠폰 이용 가능 시작 시각입니다.", example = "11:30")
        LocalTime usableStartTime,

        @Schema(description = "쿠폰 이용 가능 종료 시각입니다.", example = "13:00")
        LocalTime usableEndTime,

        @Schema(description = "최소 주문 금액입니다. 입력하지 않았으면 null입니다.")
        Integer minOrderAmount,

        @Schema(description = "안내 문구입니다. 입력하지 않았으면 null입니다.")
        String notice,

        @Schema(description = "노출 반경(m)입니다.", example = "1000")
        int targetRadius,

        @Schema(description = "노출 대상 성별입니다.", example = "ALL")
        TargetGender targetGender,

        @Schema(description = "노출 대상 연령대입니다.")
        List<AgeGroup> targetAgeGroups,

        @Schema(description = "하루 예산(포인트)입니다. 아직 한 번도 설정하지 않았으면 null입니다.")
        Long dailyBudget,

        @Schema(description = "집행 시작일입니다. 아직 한 번도 설정하지 않았으면 null입니다.")
        LocalDate startDate,

        @Schema(description = "집행 종료일입니다. 아직 한 번도 설정하지 않았으면 null입니다.")
        LocalDate endDate,

        @Schema(description = "상태가 마지막으로 바뀐 시각입니다.")
        LocalDateTime statusChangedAt,

        @Schema(description = "캠페인을 만든 시각입니다.")
        LocalDateTime createdAt
) {
    public static CampaignDetailResponse from(Campaign campaign) {
        return new CampaignDetailResponse(
                campaign.getId(), campaign.getStoreId(), campaign.getStatus(), campaign.getPausedReason(),
                campaign.getDiscountTargetType(), campaign.getDiscountType(), campaign.getDiscountValue(),
                campaign.getTargetMenuId(), campaign.getIssueQuantity(),
                campaign.getUsableStartTime(), campaign.getUsableEndTime(),
                campaign.getMinOrderAmount(), campaign.getNotice(),
                campaign.getTargetRadius(), campaign.getTargetGender(), campaign.getTargetAgeGroups(),
                campaign.getDailyBudget(), campaign.getStartDate(), campaign.getEndDate(),
                campaign.getStatusChangedAt(), campaign.getCreatedAt());
    }
}
