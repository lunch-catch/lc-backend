package com.lunchcatch.campaign.contract;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public record CampaignInfo(
        Long campaignId,
        Long ownerId,
        Long storeId,
        CampaignStatus status,
        int issueQuantity,
        LocalTime usableStartTime,
        LocalTime usableEndTime,
        LocalDate startDate,
        LocalDate endDate,
        DiscountTargetType discountTargetType,
        DiscountType discountType,
        int discountValue,
        Long targetMenuId,
        int targetRadius,
        TargetGender targetGender,
        List<AgeGroup> targetAgeGroups,
        LocalDateTime createdAt,
        LocalDate newPriorityUntil,
        LocalDateTime statusChangedAt,
        PauseReason pauseReason,
        Long dailyBudget
) {
    public CampaignInfo {
        targetAgeGroups = List.copyOf(targetAgeGroups);
    }
}
