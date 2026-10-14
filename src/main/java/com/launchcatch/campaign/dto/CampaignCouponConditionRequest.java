package com.launchcatch.campaign.dto;

import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

public record CampaignCouponConditionRequest(
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
