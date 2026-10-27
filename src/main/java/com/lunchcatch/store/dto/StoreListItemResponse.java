package com.lunchcatch.store.dto;

import com.lunchcatch.store.contract.StoreDiscountTargetType;
import com.lunchcatch.store.contract.StoreDiscountType;
import java.math.BigDecimal;

public record StoreListItemResponse(
        long storeId, String name, String storeImageUrl, String businessCategory, BigDecimal longitude, BigDecimal latitude,
        long distanceMeters, boolean hasActiveCampaign, ActiveCampaign activeCampaign
) {
    public static StoreListItemResponse from(StoreListRow row, String storeImageUrl) {
        ActiveCampaign campaign = row.campaignId() == null
                || (row.discountTargetType() == StoreDiscountTargetType.MENU && row.targetMenuId() == null) ? null
                : new ActiveCampaign(row.discountTargetType(), row.discountType(), row.discountValue(), targetMenu(row));
        // 정렬과 커서에 쓰는 소수 거리는 그대로 두고 응답에 표시할 거리만 정수 미터로 반올림한다.
        return new StoreListItemResponse(row.storeId(), row.name(), storeImageUrl, row.businessCategory(), row.longitude(),
                row.latitude(), Math.round(row.distanceMeters()), campaign != null, campaign);
    }

    private static TargetMenu targetMenu(StoreListRow row) {
        if (row.discountTargetType() != StoreDiscountTargetType.MENU) {
            return null;
        }
        return new TargetMenu(row.targetMenuId(), row.targetMenuName(), row.targetMenuPrice());
    }

    public record ActiveCampaign(StoreDiscountTargetType discountTargetType, StoreDiscountType discountType,
            int discountValue, TargetMenu targetMenu) { }

    public record TargetMenu(long menuId, String name, long price) { }
}
