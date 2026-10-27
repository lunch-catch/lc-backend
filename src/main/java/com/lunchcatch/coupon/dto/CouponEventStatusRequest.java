package com.lunchcatch.coupon.dto;

import com.lunchcatch.coupon.CouponQueryLimits;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hibernate.validator.constraints.UniqueElements;

@Schema(description = "쿠폰 이벤트 상태를 조회할 매장 목록이다.", requiredProperties = {"storeIds"})
public record CouponEventStatusRequest(
        @ArraySchema(arraySchema = @Schema(description = "입력 순서대로 조회할 매장 ID다.", example = "[10, 20, 30]"),
                schema = @Schema(type = "integer", format = "int64", minimum = "1"),
                minItems = 1, maxItems = CouponQueryLimits.MAX_STORE_IDS, uniqueItems = true)
        @NotEmpty @Size(min = 1, max = CouponQueryLimits.MAX_STORE_IDS) @UniqueElements
        List<@NotNull @Positive Long> storeIds
) {
}
