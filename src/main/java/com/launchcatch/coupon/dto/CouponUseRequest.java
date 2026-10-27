package com.launchcatch.coupon.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(description = "QR 토큰으로 쿠폰 사용을 요청한다.")
public record CouponUseRequest(
        @DecimalMin("-180") @DecimalMax("180")
        @Schema(description = "호환성을 위해 받는 선택 경도이며 사용 승인에 쓰지 않는다.", example = "127.0") Double longitude,
        @DecimalMin("-90") @DecimalMax("90")
        @Schema(description = "호환성을 위해 받는 선택 위도이며 사용 승인에 쓰지 않는다.", example = "37.5") Double latitude,
        @NotNull @Schema(description = "스캔한 QR 토큰이다.", format = "uuid") UUID qrToken
) {
}
