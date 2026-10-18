package com.launchcatch.store.dto;

import com.launchcatch.store.contract.StoreDiscountTargetType;
import com.launchcatch.store.contract.StoreDiscountType;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

public record StoreDetailResponse(long storeId, String name, String businessCategory, String roadAddress,
        String phone, BigDecimal latitude, BigDecimal longitude, List<BusinessHour> businessHours,
        Image logoImage, List<Image> interiorImages, List<Menu> menus, ActiveCampaign activeCampaign) {
    public StoreDetailResponse {
        businessHours = List.copyOf(businessHours);
        interiorImages = List.copyOf(interiorImages);
        menus = List.copyOf(menus);
    }

    public record BusinessHour(String dayOfWeek, LocalTime openTime, LocalTime closeTime,
            boolean isClosed, LocalTime breakStartTime, LocalTime breakEndTime, LocalTime lastOrderTime) { }

    public record Image(String objectKey, String url, Integer sortOrder) { }

    public record Menu(Long menuId, String name, long price, String description, int sortOrder, Image image) { }

    public record ActiveCampaign(long campaignId, StoreDiscountTargetType discountTargetType,
            StoreDiscountType discountType, int discountValue, TargetMenu targetMenu,
            LocalTime usableStartTime, LocalTime usableEndTime) { }

    public record TargetMenu(long menuId, String name, long price) { }
}
