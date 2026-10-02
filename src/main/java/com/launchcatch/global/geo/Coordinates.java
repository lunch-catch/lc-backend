package com.launchcatch.global.geo;

/*
 * 위경도 범위 검사. DB 가 CHECK 제약으로 같은 범위를 막지만(V1 의 ck_store_latitude 등),
 * 외부에서 들어온 좌표는 저장 전에 계산에 먼저 쓰인다. 범위를 벗어난 좌표는 예외 없이
 * 그럴듯한 거리를 내놓아 조용히 틀리므로 계산 앞에서 막는다.
 *
 * BusinessException 이 아니라 IllegalArgumentException 을 던진다. 사용자 입력은 컨트롤러의
 * Bean Validation 이 먼저 걸러 400 INVALID_INPUT 으로 내보내므로, 여기까지 범위 밖 좌표가
 * 닿았다면 호출하는 쪽의 버그다. 복구 가능한 실패와 프로그래밍 오류를 구분하라는 EJ-9-02 와
 * 표준 예외를 먼저 쓰라는 EJ-9-04 에 따라 500 INTERNAL_ERROR 로 가는 쪽이 맞다.
 * global 은 도메인 지식이 없어 들고 갈 ErrorCode 도 없다(설계 문서 36행).
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
