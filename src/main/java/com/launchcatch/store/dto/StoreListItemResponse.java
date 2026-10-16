package com.launchcatch.store.dto;

import com.launchcatch.store.contract.StoreDiscountTargetType;
import com.launchcatch.store.contract.StoreDiscountType;
import java.math.BigDecimal;

public record StoreListItemResponse(
        long storeId, String name, String businessCategory, BigDecimal longitude, BigDecimal latitude,
        long distanceMeters, boolean hasActiveCampaign, ActiveCampaign activeCampaign
) {
    public static StoreListItemResponse from(StoreListRow row) {
        ActiveCampaign campaign = row.campaignId() == null ? null
                : new ActiveCampaign(row.discountTargetType(), row.discountType(), row.discountValue(), targetMenu(row));
        // 정렬과 커서에 쓰는 소수 거리는 그대로 두고 응답에 표시할 거리만 정수 미터로 반올림한다.
        return new StoreListItemResponse(row.storeId(), row.name(), row.businessCategory(), row.longitude(),
                row.latitude(), Math.round(row.distanceMeters()), campaign != null, campaign);
    }

    private static TargetMenu targetMenu(StoreListRow row) {
        if (row.discountTargetType() != StoreDiscountTargetType.MENU) {
            return null;
        }
        if (row.targetMenuId() == null) {
            // MENU 요약의 메뉴 누락은 데이터 오류이므로 캠페인 미보유 상태로 숨기지 않는다.
            throw new IllegalStateException("활성 캠페인의 할인 대상 메뉴가 누락되었습니다.");
        }
        return new TargetMenu(row.targetMenuId(), row.targetMenuName(), row.targetMenuPrice());
    }

    public record ActiveCampaign(StoreDiscountTargetType discountTargetType, StoreDiscountType discountType,
            int discountValue, TargetMenu targetMenu) { }

    public record TargetMenu(long menuId, String name, long price) { }
}
