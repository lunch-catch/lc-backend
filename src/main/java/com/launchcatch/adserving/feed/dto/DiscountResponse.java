package com.launchcatch.adserving.feed.dto;

import com.launchcatch.adserving.engine.entity.DiscountTarget;
import com.launchcatch.adserving.engine.entity.DiscountType;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "카드의 할인 내용이다.", requiredProperties = {"target", "type", "value"})
public record DiscountResponse(
        @Schema(description = "ALL은 전체 메뉴, MENU는 특정 메뉴다.", example = "ALL")
        DiscountTarget target,
        @Schema(description = "PERCENT는 할인율, AMOUNT는 할인 금액이다.", example = "PERCENT")
        DiscountType type,
        @Schema(description = "PERCENT면 1 ~ 100(%), AMOUNT면 원이다.", example = "20")
        int value
) {
}
