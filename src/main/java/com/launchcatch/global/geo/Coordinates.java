package com.launchcatch.global.geo;

/*
 * 위경도 범위 검사. DB 가 CHECK 제약으로 같은 범위를 막지만(V1 의 ck_store_latitude 등),
 * 외부에서 들어온 좌표는 저장 전에 계산에 먼저 쓰인다. 범위를 벗어난 좌표는 예외 없이
 * 그럴듯한 거리를 내놓아 조용히 틀리므로 계산 앞에서 막는다.
 */
final class Coordinates {

    static final double MIN_LATITUDE = -90.0;
    static final double MAX_LATITUDE = 90.0;
    static final double MIN_LONGITUDE = -180.0;
    static final double MAX_LONGITUDE = 180.0;

    private Coordinates() {
    }

    static void requireLatitude(double latitude) {
        if (Double.isNaN(latitude) || latitude < MIN_LATITUDE || latitude > MAX_LATITUDE) {
            throw new IllegalArgumentException("위도는 -90 과 90 사이여야 한다: " + latitude);
        }
    }

    static void requireLongitude(double longitude) {
        if (Double.isNaN(longitude) || longitude < MIN_LONGITUDE || longitude > MAX_LONGITUDE) {
            throw new IllegalArgumentException("경도는 -180 과 180 사이여야 한다: " + longitude);
        }
    }
}
