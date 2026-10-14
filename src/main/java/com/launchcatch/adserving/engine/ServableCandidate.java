package com.launchcatch.adserving.engine;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

// 그날 후보 스냅샷(ad_candidate)에서 서빙 가능한 행 하나다. 소진과 상태는 조회에서 이미 걸렀다.
public record ServableCandidate(
        Long campaignId,
        Long storeId,
        String storeName,
        String categoryCode,
        double latitude,
        double longitude,
        int targetRadiusM,
        String targetGender,
        Set<String> targetAgeGroups,
        String discountTargetType,
        String discountType,
        int discountValue,
        LocalTime usableStartTime,
        LocalTime usableEndTime,
        int issueQuantity,
        LocalDateTime campaignCreatedAt
) {

    public ServableCandidate {
        targetAgeGroups = Set.copyOf(targetAgeGroups);
    }
}
