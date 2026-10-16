package com.launchcatch.coupon.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

@Schema(description = "점주의 스캔 위치와 할인 전 전체 주문 금액으로 쿠폰 사용을 요청한다.")
public record CouponUseRequest(
        @NotNull @DecimalMin("-180") @DecimalMax("180")
        @Schema(description = "QR을 스캔한 점주 위치의 경도다.", example = "127.0") Double longitude,
        @NotNull @DecimalMin("-90") @DecimalMax("90")
        @Schema(description = "QR을 스캔한 점주 위치의 위도다.", example = "37.5") Double latitude,
        @NotNull @Schema(description = "스캔한 QR 토큰이다.", format = "uuid") UUID qrToken,
        @NotNull @Positive @Max(Integer.MAX_VALUE)
        @Schema(description = "할인 전 전체 주문 금액이며 원 단위 정수다.", example = "10000",
                minimum = "1", maximum = "2147483647") Long orderAmount
) {
}
