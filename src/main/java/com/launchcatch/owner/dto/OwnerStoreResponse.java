package com.launchcatch.owner.dto;

import com.launchcatch.store.contract.OwnerStoreSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public record OwnerStoreResponse(Long storeId, boolean finalized, String name, String businessCategory,
        String representativeName, String businessRegistrationNumber, String phone,
        String businessVerificationStatus, LocalDateTime businessVerifiedAt,
        String kakaoPlaceId, String roadAddress, BigDecimal latitude, BigDecimal longitude,
        List<BusinessHour> businessHours, Image logoImage, List<Image> interiorImages, List<Menu> menus) {
    public static OwnerStoreResponse from(OwnerStoreSnapshot store) {
        return new OwnerStoreResponse(store.storeId(), store.finalized(), store.name(), store.businessCategory(),
                store.representativeName(), store.businessRegistrationNumber(), store.phone(),
                store.businessVerificationStatus(), store.businessVerifiedAt(), store.kakaoPlaceId(),
                store.roadAddress(), store.latitude(), store.longitude(), store.businessHours().stream().map(hour ->
                    new BusinessHour(hour.dayOfWeek(), hour.openTime(), hour.closeTime(), hour.isClosed(),
                        hour.breakStartTime(), hour.breakEndTime(), hour.lastOrderTime())).toList(),
                image(store.logoImage()), store.interiorImages().stream().map(OwnerStoreResponse::image).toList(),
                store.menus().stream().map(menu -> new Menu(menu.menuId(), menu.name(), menu.price(),
                    menu.description(), menu.sortOrder(), image(menu.image()))).toList());
    }

    private static Image image(OwnerStoreSnapshot.Image image) {
        return image == null ? null : new Image(image.objectKey(), image.url(), image.sortOrder());
    }

    public record BusinessHour(String dayOfWeek, LocalTime openTime, LocalTime closeTime,
            boolean isClosed, LocalTime breakStartTime, LocalTime breakEndTime, LocalTime lastOrderTime) { }
    public record Image(String objectKey, String url, Integer sortOrder) { }
    public record Menu(Long menuId, String name, long price, String description, int sortOrder,
            Image image) { }
}