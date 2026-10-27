package com.lunchcatch.coupon.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "트랜잭션 커밋이 완료된 쿠폰의 발급 결과다.")
public record CouponIssueResponse(
        @Schema(description = "발급한 회원 쿠폰 ID다.", example = "1001") Long memberCouponId,
        @Schema(description = "DB에 저장한 발급 시각이며 한국 시간 오프셋을 포함한다.", example = "2026-10-15T11:00:00+09:00")
        OffsetDateTime issueTime
) {
}
