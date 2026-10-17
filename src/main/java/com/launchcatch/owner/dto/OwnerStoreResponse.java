package com.launchcatch.owner.dto;

import com.launchcatch.store.contract.OwnerStoreOverview;
import com.launchcatch.store.contract.OwnerStoreSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public record OwnerStoreResponse(Long storeId, boolean finalized, String name, String businessCategory,
        String representativeName, String businessRegistrationNumber, String phone,
        String businessVerificationStatus, LocalDateTime businessVerifiedAt,
        String kakaoPlaceId, String roadAddress, BigDecimal latitude, BigDecimal longitude,
        List<BusinessHour> businessHours, Image logoImage, List<Image> interiorImages, List<Menu> menus, Onboarding onboarding) {
    public static OwnerStoreResponse from(OwnerStoreOverview overview) {
        var store = overview.store();
        var value = overview.onboarding();
        var agreement = value.termsAgreement();
        var onboarding = new Onboarding(value.currentStep(), value.resumeStep(), value.version(), value.canComplete(),
                agreement == null ? null : new TermsAgreement(agreement.termsType(), agreement.termsVersion(),
                    agreement.required(), agreement.agreed(), agreement.agreedAt(), agreement.recordedAt(),
                    agreement.withdrawnAt()), value.steps().stream().map(step -> new Step(step.step(), step.optional(),
                        step.hasSavedData(), step.completed())).toList());
        if (store == null) {
            return new OwnerStoreResponse(null, false, null, null, null, null, null, null, null,
                    null, null, null, null, List.of(), null, List.of(), List.of(), onboarding);
        }
        return new OwnerStoreResponse(store.storeId(), store.finalized(), store.name(), store.businessCategory(),
                store.representativeName(), store.businessRegistrationNumber(), store.phone(),
                store.businessVerificationStatus(), store.businessVerifiedAt(), store.kakaoPlaceId(),
                store.roadAddress(), store.latitude(), store.longitude(), store.businessHours().stream().map(hour ->
                    new BusinessHour(hour.dayOfWeek(), hour.openTime(), hour.closeTime(), hour.isClosed(),
                        hour.breakStartTime(), hour.breakEndTime(), hour.lastOrderTime())).toList(),
                image(store.logoImage()), store.interiorImages().stream().map(OwnerStoreResponse::image).toList(),
                store.menus().stream().map(menu -> new Menu(menu.menuId(), menu.name(), menu.price(),
                    menu.description(), menu.sortOrder(), image(menu.image()))).toList(), onboarding);
    }

    private static Image image(OwnerStoreSnapshot.Image image) {
        return image == null ? null : new Image(image.objectKey(), image.url(), image.sortOrder());
    }

    public record BusinessHour(String dayOfWeek, LocalTime openTime, LocalTime closeTime,
            boolean isClosed, LocalTime breakStartTime, LocalTime breakEndTime, LocalTime lastOrderTime) { }
    public record Image(String objectKey, String url, Integer sortOrder) { }
    public record Menu(Long menuId, String name, long price, String description, int sortOrder,
            Image image) { }

    public record Onboarding(String currentStep, String resumeStep, long version, boolean canComplete,
            TermsAgreement termsAgreement, List<Step> steps) { }
    public record TermsAgreement(String termsType, String termsVersion, boolean required, boolean agreed,
            LocalDateTime agreedAt, LocalDateTime recordedAt, LocalDateTime withdrawnAt) { }
    public record Step(String step, boolean optional, boolean hasSavedData, boolean completed) { }
}
