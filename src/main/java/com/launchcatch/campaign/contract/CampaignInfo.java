package com.launchcatch.campaign.contract;

import java.time.LocalDate;
import java.time.LocalTime;

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
        Long targetMenuId
) {
}
