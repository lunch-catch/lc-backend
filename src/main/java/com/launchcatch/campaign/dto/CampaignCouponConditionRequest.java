package com.launchcatch.campaign.dto;

import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

/*
 * storeId는 명세서(campaign-api-spec.md)에 없는 필드다. store 도메인의 공개 contract가
 * 아직 없어 로그인한 점주(ownerId)로 storeId를 서버가 직접 찾을 방법이 없다(#244). 쿠폰
 * 도메인의 /v1/owner/stores/{storeId}/coupon-usages와 같은 이유로 클라이언트가 보낸 값을
 * 그대로 믿는다. 이 storeId가 실제로 그 점주의 가게인지는 검증하지 않는다.
 */
public record CampaignCouponConditionRequest(
        @Schema(description = "점주의 가게 ID입니다.", example = "17", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Long storeId,

        @Schema(description = "할인 대상입니다.", example = "MENU", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull DiscountTargetType discountTargetType,

        @Schema(description = "할인 방식입니다.", example = "PERCENT", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull DiscountType discountType,

        @Schema(description = "할인값입니다. PERCENT면 100 이하여야 합니다.", example = "15",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Integer discountValue,

        @Schema(description = "discountTargetType이 MENU일 때만 필요한 대상 메뉴 ID입니다.", example = "42")
        Long targetMenuId,

        @Schema(description = "발급 수량입니다. 1 이상이어야 합니다.", example = "100",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Integer issueQuantity,

        @Schema(description = "쿠폰 사용 가능 시작 시각입니다. 11:30~15:00 안, 30분 단위여야 합니다.", example = "11:30",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull LocalTime usableStartTime,

        @Schema(description = "쿠폰 사용 가능 종료 시각입니다. usableStartTime보다 최소 1시간 뒤여야 합니다.",
                example = "13:00", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull LocalTime usableEndTime,

        @Schema(description = "최소 주문 금액입니다. 선택 입력입니다.", example = "8000")
        Integer minOrderAmount,

        @Schema(description = "쿠폰 안내 문구입니다. 500자 이하입니다. 선택 입력입니다.",
                example = "1인 1매, 포장만 가능")
        @Size(max = 500) String notice
) {
}
