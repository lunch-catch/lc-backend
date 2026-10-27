package com.lunchcatch.store.repository;

import com.lunchcatch.store.contract.StoreDiscountTargetType;
import com.lunchcatch.store.contract.StoreDiscountType;
import java.math.BigDecimal;
import java.time.LocalTime;

// store 내부 조회 결과이며 HTTP 응답이나 다른 도메인에 공개하는 계약이 아니다.
public record StoreDetailData(long storeId, String name, String businessCategory, String roadAddress,
        String phone, BigDecimal latitude, BigDecimal longitude) {
    public record Image(String objectKey, Integer sortOrder) { }

    public record Campaign(long campaignId, StoreDiscountTargetType discountTargetType,
            StoreDiscountType discountType, int discountValue, Long targetMenuId,
            String targetMenuName, Long targetMenuPrice, LocalTime usableStartTime, LocalTime usableEndTime) { }
}
