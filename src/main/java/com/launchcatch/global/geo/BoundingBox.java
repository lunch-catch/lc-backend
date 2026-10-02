package com.launchcatch.global.geo;

/*
 * 반경 검색의 1차 걸름용 사각 범위. 하버사인은 컬럼을 함수로 감싸야 해서 인덱스를 타지
 * 못하므로, 먼저 latitude 와 longitude 의 범위 비교로 후보를 줄이고(V1 의
 * idx_candidate_geo 가 이 비교를 받는다) 남은 후보에만 GeoDistance 를 적용한다.
 *
 * 상자는 원을 반드시 모두 덮는다. 덜 덮으면 반경 안의 가게가 후보에서 빠져 조용히
 * 사라지고, 더 덮는 쪽은 뒤따르는 거리 계산이 걸러 내므로 손해가 없다.
 *
 * 같은 타입 인자가 넷이라 빌더를 둘 자리로 보이지만(EJ-1-02) 두지 않았다. 만드는 길은
 * around 팩터리 하나이고, 직접 생성하는 쪽의 실수는 아래 검사가 잡는다. 하한과 상한을
 * 뒤집으면 범위 비교에서 걸리고, 위도와 경도를 맞바꾸면 90 을 넘는 값이 범위 검사에서
 * 걸린다. 한국 좌표는 경도가 127 대라 맞바꾸는 실수가 반드시 걸린다.
 */
public record BoundingBox(
        double minLatitude,
        double maxLatitude,
        double minLongitude,
        double maxLongitude
) {

    /*
     * 경도 폭을 구할 때 cos 으로 나눈다. 극에 가까워지면 cos 이 0 으로 떨어져 폭이
     * 발산하므로, 그 전에 경도 전체로 넓혀 버린다. 이 값은 위도 약 89.43도에 해당한다.
     */
    private static final double MIN_COSINE = 0.01;

    public BoundingBox {
        Coordinates.requireLatitude(minLatitude);
        Coordinates.requireLatitude(maxLatitude);
        Coordinates.requireLongitude(minLongitude);
        Coordinates.requireLongitude(maxLongitude);
        if (minLatitude > maxLatitude) {
            throw new IllegalArgumentException("위도 하한이 상한보다 크다: " + minLatitude + " > " + maxLatitude);
        }
        if (minLongitude > maxLongitude) {
            throw new IllegalArgumentException("경도 하한이 상한보다 크다: " + minLongitude + " > " + maxLongitude);
        }
    }

    /** 한 좌표에서 반경 radiusMeters 의 원을 모두 덮는 상자를 만든다. */
    public static BoundingBox around(double latitude, double longitude, double radiusMeters) {
        Coordinates.requireLatitude(latitude);
        Coordinates.requireLongitude(longitude);
        if (Double.isNaN(radiusMeters) || radiusMeters < 0) {
            throw new IllegalArgumentException("반경은 0 이상이어야 한다: " + radiusMeters);
        }

        double latitudeDelta = Math.toDegrees(radiusMeters / GeoDistance.EARTH_RADIUS_METERS);
        double minLatitude = Math.max(latitude - latitudeDelta, Coordinates.MIN_LATITUDE);
        double maxLatitude = Math.min(latitude + latitudeDelta, Coordinates.MAX_LATITUDE);

        /*
         * 같은 거리라도 고위도로 갈수록 경도 폭이 넓어진다. 상자 안에서 가장 넓어지는
         * 위도를 기준으로 폭을 잡아야 원이 상자를 삐져나가지 않는다.
         */
        double widestLatitude = Math.max(Math.abs(minLatitude), Math.abs(maxLatitude));
        double cosine = Math.cos(Math.toRadians(widestLatitude));
        if (cosine < MIN_COSINE) {
            return wholeLongitude(minLatitude, maxLatitude);
        }

        double longitudeDelta = Math.toDegrees(radiusMeters / (GeoDistance.EARTH_RADIUS_METERS * cosine));
        double minLongitude = longitude - longitudeDelta;
        double maxLongitude = longitude + longitudeDelta;

        /*
         * 자정경선을 넘으면 범위가 두 토막으로 끊긴다. 범위 비교 하나로 처리하려고
         * 경도 전체로 넓힌다. 한국 영역에서는 일어나지 않는 경우다.
         */
        if (minLongitude < Coordinates.MIN_LONGITUDE || maxLongitude > Coordinates.MAX_LONGITUDE) {
            return wholeLongitude(minLatitude, maxLatitude);
        }
        return new BoundingBox(minLatitude, maxLatitude, minLongitude, maxLongitude);
    }

    /** 좌표가 상자 안에 있는지 본다. 경계선 위는 안으로 센다. */
    public boolean contains(double latitude, double longitude) {
        Coordinates.requireLatitude(latitude);
        Coordinates.requireLongitude(longitude);
        return latitude >= minLatitude && latitude <= maxLatitude
                && longitude >= minLongitude && longitude <= maxLongitude;
    }

    private static BoundingBox wholeLongitude(double minLatitude, double maxLatitude) {
        return new BoundingBox(minLatitude, maxLatitude, Coordinates.MIN_LONGITUDE, Coordinates.MAX_LONGITUDE);
    }
}
