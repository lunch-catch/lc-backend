package com.lunchcatch.coupon.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "본인 쿠폰의 QR 이미지와 토큰 만료 시각이다.", requiredProperties = {"qrImageBytes", "expireTime"})
public record CouponQrResponse(
        @Schema(description = "QR 토큰을 담은 PNG 바이트를 Base64로 인코딩한 문자열이다.", format = "byte")
        String qrImageBytes,
        @Schema(description = "생성 시점부터 60초 후인 QR 토큰 만료 시각이다.", example = "2026-10-10T12:01:00+09:00")
        OffsetDateTime expireTime
) {
}
