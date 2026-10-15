package com.launchcatch.adserving.engine;

import com.launchcatch.adserving.engine.entity.DiscountTarget;
import com.launchcatch.adserving.engine.entity.DiscountType;
import com.launchcatch.adserving.engine.entity.TargetGender;
import java.time.LocalTime;
import java.util.Set;

// 카드를 고르고 그리는 데 쓰는 그날 후보 한 건이다. 계산이 엔티티를 받지 않게 옮겨 담는다.
public record FeedCandidate(
        Long campaignId,
        Long storeId,
        String storeName,
        String categoryCode,
        double latitude,
        double longitude,
        int targetRadiusM,
        TargetGender targetGender,
        Set<String> targetAgeGroups,
        DiscountTarget discountTarget,
        DiscountType discountType,
        int discountValue,
        LocalTime usableStartTime,
        LocalTime usableEndTime,
        int issueQuantity
) {
}
