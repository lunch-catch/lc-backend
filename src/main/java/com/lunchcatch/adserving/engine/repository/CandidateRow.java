package com.lunchcatch.adserving.engine.repository;

import com.lunchcatch.adserving.engine.entity.CandidateCampaignState;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

// 00:00 적재가 넣는 그날 후보 한 행이다. 열은 ad_candidate 와 같고 소진 시각은 넣지 않는다
public record CandidateRow(
        LocalDate businessDate,
        Long campaignId,
        Long storeId,
        String storeName,
        String storeImageUrl,
        String categoryCode,
        BigDecimal latitude,
        BigDecimal longitude,
        int targetRadius,
        String targetGender,
        String targetAgeGroups,
        String discountTargetType,
        String discountType,
        int discountValue,
        String discountMenuName,
        LocalTime usableStartTime,
        LocalTime usableEndTime,
        int issueQuantity,
        LocalDateTime campaignCreatedAt,
        LocalDate newPriorityUntil,
        CandidateCampaignState campaignState,
        LocalDateTime stateChangedAt
) {
}
