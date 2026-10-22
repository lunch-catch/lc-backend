package com.launchcatch.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "대표 저장 위치. 저장 위치가 없으면 모든 필드가 null입니다.")
public record MemberLocationResponse(
        @Schema(description = "위치 별칭입니다.", example = "회사", nullable = true)
        String locationNickname,
        @Schema(description = "도로명 주소입니다.", example = "서울특별시 강남구 테헤란로 1", nullable = true)
        String roadAddress,
        @Schema(description = "위도입니다.", example = "37.4979000", nullable = true)
        BigDecimal latitude,
        @Schema(description = "경도입니다.", example = "127.0276000", nullable = true)
        BigDecimal longitude
) {
}
