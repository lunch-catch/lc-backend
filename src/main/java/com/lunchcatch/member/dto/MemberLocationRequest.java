package com.lunchcatch.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(description = "대표 저장 위치 입력. 위도와 경도는 모두 필수이며 별칭과 주소는 선택입니다.")
public record MemberLocationRequest(
        @Schema(description = "위치 별칭. 생략하면 기존 별칭을 비웁니다.", example = "회사", maxLength = 50, nullable = true)
        @Size(max = 50, message = "위치 별칭은 50자 이하로 입력해 주세요.") String locationNickname,
        @Schema(description = "도로명 주소. 생략하면 기존 주소를 비웁니다.", example = "서울특별시 강남구 테헤란로 1", maxLength = 255, nullable = true)
        @Size(max = 255, message = "도로명 주소는 255자 이하로 입력해 주세요.") String roadAddress,
        @Schema(description = "위도. -90 이상 90 이하입니다.", example = "37.4979000", minimum = "-90", maximum = "90", requiredMode = Schema.RequiredMode.REQUIRED)
        @DecimalMin(value = "-90.0", message = "위도는 -90 이상이어야 합니다.")
        @DecimalMax(value = "90.0", message = "위도는 90 이하여야 합니다.") BigDecimal latitude,
        @Schema(description = "경도. -180 이상 180 이하입니다.", example = "127.0276000", minimum = "-180", maximum = "180", requiredMode = Schema.RequiredMode.REQUIRED)
        @DecimalMin(value = "-180.0", message = "경도는 -180 이상이어야 합니다.")
        @DecimalMax(value = "180.0", message = "경도는 180 이하여야 합니다.") BigDecimal longitude
) {
    @Schema(hidden = true)
    @AssertTrue(message = "위도와 경도를 모두 입력해 주세요.")
    public boolean hasCoordinatePair() {
        return latitude != null && longitude != null;
    }
}
