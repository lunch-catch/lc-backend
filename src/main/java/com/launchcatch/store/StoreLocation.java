package com.launchcatch.store;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record StoreLocation(String kakaoPlaceId, String roadAddress, BigDecimal latitude, BigDecimal longitude) {
    public StoreLocation {
        requireText(kakaoPlaceId, 50);
        requireText(roadAddress, 255);
        requireCoordinate(latitude, 90);
        requireCoordinate(longitude, 180);
        latitude = latitude.setScale(7, RoundingMode.HALF_UP);
        longitude = longitude.setScale(7, RoundingMode.HALF_UP);
    }

    private static void requireText(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("장소 ID와 도로명 주소는 허용 길이 내의 필수 문자열이어야 합니다.");
        }
    }

    private static void requireCoordinate(BigDecimal value, int limit) {
        if (value == null || value.abs().compareTo(BigDecimal.valueOf(limit)) > 0) {
            throw new IllegalArgumentException("좌표는 허용 범위 내의 필수 숫자여야 합니다.");
        }
    }
}
