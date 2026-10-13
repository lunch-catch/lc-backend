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
 * 그날 후보 스냅샷(ad_candidate)의 한 행이다.
 * 서빙 경로는 이 값만 읽는다.
 */
public record Candidate(
        Long campaignId,
        Long storeId,
        String storeName,
        String categoryCode,
        double latitude,
        double longitude,
        int targetRadius,
        TargetGender targetGender,
        Set<AgeGroup> targetAgeGroups,
        DiscountTargetType discountTargetType,
        DiscountType discountType,
        int discountValue,
        LocalTime usableStartTime,
        LocalTime usableEndTime,
        int issueQuantity,
        LocalDateTime campaignCreatedAt,
        LocalDate newPriorityUntil
) {

    public Candidate {
        targetAgeGroups = Set.copyOf(targetAgeGroups);
    }

    /*
     * 캠페인이 고른 성별과 연령대에 회원이 드는지 본다.
     * 회원 성별 OTHER 는 성별을 고르지 않은 캠페인(ALL)에만 든다.
     */
    public boolean targets(String memberGender, String memberAgeGroup) {
        boolean genderMatches = targetGender == TargetGender.ALL || targetGender.name().equals(memberGender);
        return genderMatches && targetAgeGroups.stream().anyMatch(group -> group.name().equals(memberAgeGroup));
    }

    // 오늘이 신규 우선 노출 기간 안이면 참이다
    public boolean newPriorityOn(LocalDate businessDate) {
        return newPriorityUntil != null && !businessDate.isAfter(newPriorityUntil);
    }
}
