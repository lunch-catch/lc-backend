package com.lunchcatch.store.contract;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public record OwnerStoreSnapshot(Long storeId, boolean finalized, String name, String businessCategory,
        String representativeName, String businessRegistrationNumber, String phone,
        String businessVerificationStatus, LocalDateTime businessVerifiedAt,
        String kakaoPlaceId, String roadAddress, BigDecimal latitude, BigDecimal longitude,
        List<BusinessHour> businessHours, Image logoImage, List<Image> interiorImages, List<Menu> menus) {
    public OwnerStoreSnapshot {
        businessHours = List.copyOf(businessHours);
        interiorImages = List.copyOf(interiorImages);
        menus = List.copyOf(menus);
    }
    public record BusinessHour(String dayOfWeek, LocalTime openTime, LocalTime closeTime,
            boolean isClosed, LocalTime breakStartTime, LocalTime breakEndTime, LocalTime lastOrderTime) { }
    public record Image(String objectKey, String url, Integer sortOrder) { }
    public record Menu(Long menuId, String name, long price, String description, int sortOrder,
            Image image) { }
}
