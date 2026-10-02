package com.launchcatch.global.geo;

/*
 * 두 좌표 사이의 지표면 거리. 피드가 "내 주변 가게"를 고르고 광고 서빙이 노출 후보를
 * 거를 때 쓴다. 지구를 반지름 하나의 구로 보는 하버사인 공식이라 한국 안의 거리에서는
 * 타원체 공식(Vincenty)과의 차이가 0.3% 안쪽이고, 계산이 짧아 후보 수천 건을
 * 한 요청에서 훑어도 부담이 없다.
 */
public final class GeoDistance {

    /*
     * IUGG 가 정한 지구 평균 반지름이다. 적도 반지름(6378137m)이나 극 반지름(6356752m)을
     * 쓰면 한쪽 방향 거리가 치우친다.
     */
    static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private GeoDistance() {
    }

    /** 두 좌표 사이의 거리를 미터로 돌려준다. 순서를 바꿔도 결과가 같다. */
    public static double meters(double latitude1, double longitude1, double latitude2, double longitude2) {
        Coordinates.requireLatitude(latitude1);
        Coordinates.requireLongitude(longitude1);
        Coordinates.requireLatitude(latitude2);
        Coordinates.requireLongitude(longitude2);

        double phi1 = Math.toRadians(latitude1);
        double phi2 = Math.toRadians(latitude2);
        double deltaPhi = phi2 - phi1;
        double deltaLambda = Math.toRadians(longitude2 - longitude1);

        /*
         * asin 을 쓰는 형태다. 교과서에 자주 나오는 atan2 형태와 값은 같지만,
         * 가까운 두 점에서 부동소수 오차가 덜 쌓인다.
         */
        double h = Math.pow(Math.sin(deltaPhi / 2), 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.pow(Math.sin(deltaLambda / 2), 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(Math.min(1.0, h)));
    }
}
