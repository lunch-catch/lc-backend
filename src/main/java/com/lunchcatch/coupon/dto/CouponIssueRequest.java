package com.lunchcatch.coupon.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.annotation.JsonDeserialize;

@Schema(description = "오늘의 쿠폰 이벤트에서 발급을 요청한다.")
public record CouponIssueRequest(
        @NotNull @Positive @JsonDeserialize(using = CouponEventIdDeserializer.class)
        @Schema(description = "발급받을 오늘의 이벤트 ID다.", example = "501") Long eventId
) {
}
