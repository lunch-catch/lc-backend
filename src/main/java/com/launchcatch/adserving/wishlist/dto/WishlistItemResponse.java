package com.launchcatch.adserving.wishlist.dto;

import com.launchcatch.adserving.engine.entity.AdCandidate;
import com.launchcatch.adserving.engine.entity.DiscountTarget;
import com.launchcatch.adserving.engine.entity.DiscountType;
import com.launchcatch.adserving.wishlist.entity.Wishlist;
import com.launchcatch.global.config.ClockConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record WishlistItemResponse(
        Long campaignId,
        Long storeId,
        @Schema(description = "상호. 오늘 노출 후보가 아닌 캠페인이면 null", nullable = true)
        String storeName,
        @Schema(description = "가게 대표 이미지 주소. 오늘 노출 후보가 아니거나 가게가 대표 이미지를 올리지 않았으면 null", nullable = true)
        String storeImageUrl,
        @Schema(description = "할인 내용. 오늘 노출 후보가 아닌 캠페인이면 null", nullable = true)
        Discount discount,
        @Schema(description = "캠페인이 끝났으면 true. 끝난 날을 빼고 3일 동안 보인 뒤 빠진다")
        boolean campaignEnded,
        @Schema(description = "찜한 시각")
        OffsetDateTime wishedAt
) {

    // 표시 값은 그날 후보 스냅샷에서 채운다. 후보가 없으면 셋을 null 로 둔다.
    public static WishlistItemResponse of(Wishlist wish, AdCandidate candidate) {
        OffsetDateTime wishedAt = wish.getCreatedAt().atZone(ClockConfig.ZONE).toOffsetDateTime();
        if (candidate == null) {
            return new WishlistItemResponse(wish.getCampaignId(), wish.getStoreId(), null, null, null,
                    wish.isCampaignEnded(), wishedAt);
        }
        return new WishlistItemResponse(wish.getCampaignId(), wish.getStoreId(), candidate.getStoreName(),
                candidate.getStoreImageUrl(),
                new Discount(candidate.getDiscountTarget(), candidate.getDiscountType(), candidate.getDiscountValue(),
                        candidate.getDiscountMenuName()),
                wish.isCampaignEnded(), wishedAt);
    }

    public record Discount(
            @Schema(description = "ALL(전체 메뉴), MENU(특정 메뉴)")
            DiscountTarget target,
            @Schema(description = "PERCENT(할인율), AMOUNT(할인 금액)")
            DiscountType type,
            @Schema(description = "PERCENT 면 1 ~ 100(%), AMOUNT 면 원")
            int value,
            @Schema(description = "할인 대상 메뉴명. target 이 MENU 일 때만 값이 있고 ALL 이면 null", nullable = true)
            String menuName
    ) {
    }
}
