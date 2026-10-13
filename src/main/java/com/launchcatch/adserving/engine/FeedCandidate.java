package com.launchcatch.adserving.engine;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

/*
 * 그날 후보 스냅샷(ad_candidate) 한 행에서 피드 배정에 쓰는 값이다.
 * 서빙 가능한 행(campaign_state = SERVABLE, sold_out_at IS NULL)만 이 모양으로 올라온다.
 */
public record FeedCandidate(
        long campaignId,
        long storeId,
        String storeName,
        String categoryCode,
        double latitude,
        double longitude,
        int targetRadius,
        TargetGender targetGender,
        Set<AgeGroup> targetAgeGroups,
        DiscountTargetType discountTarget,
        DiscountType discountType,
        int discountValue,
        LocalTime usableStartTime,
        LocalTime usableEndTime,
        int issueQuantity,
        LocalDateTime campaignCreatedAt,
        LocalDate newPriorityUntil
) {

    public FeedCandidate {
        targetAgeGroups = Set.copyOf(targetAgeGroups);
    }

    // 그날이 신규 우선 노출 기간 안인지 본다
    boolean newPriorityOn(LocalDate businessDate) {
        return newPriorityUntil != null && !newPriorityUntil.isBefore(businessDate);
    }
}
