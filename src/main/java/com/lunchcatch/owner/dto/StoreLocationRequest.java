package com.lunchcatch.owner.dto;

import com.lunchcatch.store.contract.StoreLocationSaveCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record StoreLocationRequest(
        @NotNull @PositiveOrZero @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "현재 온보딩 요청 버전", example = "2") Long version,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "프론트에서 확정한 카카오 장소 ID. 필수, 최대 50자", example = "12345678") String kakaoPlaceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "도로명 주소. 필수, 최대 255자", example = "서울 강남구 테헤란로 123") String roadAddress,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "위도. 필수, -90~90, 소수점 이하 7자리로 반올림해 저장", example = "37.5000000") BigDecimal latitude,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "경도. 필수, -180~180, 소수점 이하 7자리로 반올림해 저장", example = "127.0000000") BigDecimal longitude) {
    public StoreLocationSaveCommand toCommand() {
        return new StoreLocationSaveCommand(version, kakaoPlaceId, roadAddress, latitude, longitude);
    }
}
